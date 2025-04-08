package ca.ubc.ece.resess.wrappers

import ca.ubc.ece.resess.execute.DynamicSliceDebuggerExecutor
import ca.ubc.ece.resess.execute.RunCurrentFile
import ca.ubc.ece.resess.slicer.*
import ca.ubc.ece.resess.util.ParameterType
import ca.ubc.ece.resess.util.Statement
import ca.ubc.ece.resess.util.Variable
import com.intellij.execution.RunManager
import com.intellij.execution.Executor
import com.intellij.execution.RunnerAndConfigurationSettings
import com.intellij.execution.actions.RunConfigurationsComboBoxAction
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import ca.ubc.ece.resess.ui.SelectSlicingCriterionAction
import java.io.File
import java.nio.file.Path
import java.nio.file.Files
import kotlin.collections.ArrayList
import kotlin.collections.HashMap
import kotlin.collections.HashSet
import java.util.*

/**
 * 自定义切片算法包装器
 * 这个包装器使用一个基于依赖图的切片算法作为底层实现
 */
class CustomAlgorithmWrapper : HelperWrapper() {
    override val slicerName: String = "Custom Dependency Graph Slicer"

    private var slicingCriterion: Statement? = null
    private var currentSlice: Slice? = null
    private var sliceForMostRecentCrit: Boolean = false

    // 切片配置参数
    private var sliceDirection: SliceDirection = SliceDirection.BACKWARD
    private var includeControlDependencies: Boolean = true
    private var includeDataDependencies: Boolean = true
    private var maxDepth: Int = -1  // -1 表示无限深度
    private var includeLibraryCalls: Boolean = false

    /**
     * 切片方向枚举
     */
    enum class SliceDirection {
        FORWARD,    // 正向切片 - 从标准向前追踪影响
        BACKWARD,   // 反向切片 - 从标准向后追踪依赖
        BIDIRECTIONAL  // 双向切片 - 同时执行正向和反向切片
    }

    override fun getSlice(): Slice {
        if (currentSlice != null && sliceForMostRecentCrit) {
            return currentSlice!!
        }
        if (slicingCriterion == null) {
            throw IllegalStateException("切片标准未设置")
        } else if (slicingCriterion!!.slicingContext == null) {
            throw IllegalStateException("切片上下文未设置")
        }

        // 准备参数
        val e = slicingCriterion!!.slicingContext!!
        val project = e.project!!
        var selectedConfig = RunManager.getInstance(project).selectedConfiguration
        if (selectedConfig == null && RunConfigurationsComboBoxAction.hasRunCurrentFileItem(project)) {
            val psiFile = e.getData(CommonDataKeys.PSI_FILE)!!
            selectedConfig = RunCurrentFile.getRunConfigsForCurrentFile(psiFile, true).find { it != null }
        }
        if (selectedConfig == null)
            throw IllegalStateException("未选择配置且无当前文件配置")

        // 创建切片
        createSlice(project, selectedConfig, e.dataContext, DynamicSliceDebuggerExecutor.instance!!)?.let {
            currentSlice = it
        }
        sliceForMostRecentCrit = true
        return currentSlice!!
    }

    override fun getConfiguration(): ArrayList<ParameterSpec> {
        return ArrayList<ParameterSpec>().apply {
            add(ParameterSpec(
                "direction",
                TypeOfParameter.STATEMENT,
                "切片方向 (backward/forward/bidirectional)",
                1,
                ExtensionPoint.EDITOR_WINDOW
            ))
            add(ParameterSpec(
                "controlDeps",
                TypeOfParameter.STATEMENT,
                "包含控制依赖",
                1,
                ExtensionPoint.EDITOR_WINDOW
            ))
            add(ParameterSpec(
                "dataDeps",
                TypeOfParameter.STATEMENT,
                "包含数据依赖",
                1,
                ExtensionPoint.EDITOR_WINDOW
            ))
            add(ParameterSpec(
                "maxDepth",
                TypeOfParameter.STATEMENT,
                "最大切片深度 (-1表示无限制)",
                1,
                ExtensionPoint.EDITOR_WINDOW
            ))
            add(ParameterSpec(
                "includeLibs",
                TypeOfParameter.STATEMENT,
                "包含库调用",
                1,
                ExtensionPoint.EDITOR_WINDOW
            ))
        }
    }

    override fun setSlicingCriterion(statement: Statement, variables: ArrayList<Variable>?): Boolean {
        this.slicingCriterion = statement
        sliceForMostRecentCrit = false
        return true
    }

    override fun setParameters(values: Map<ParameterSpec, ArrayList<ParameterType>>): Boolean {
        for ((param, valueList) in values) {
            if (valueList.isEmpty()) continue

            when (param.label) {
                "direction" -> {
                    val dirStr = (valueList[0] as? String) ?: continue
                    sliceDirection = when (dirStr.lowercase()) {
                        "forward" -> SliceDirection.FORWARD
                        "backward" -> SliceDirection.BACKWARD
                        "bidirectional" -> SliceDirection.BIDIRECTIONAL
                        else -> SliceDirection.BACKWARD
                    }
                }
                "controlDeps" -> {
                    includeControlDependencies = (valueList[0] as? Boolean) ?: true
                }
                "dataDeps" -> {
                    includeDataDependencies = (valueList[0] as? Boolean) ?: true
                }
                "maxDepth" -> {
                    maxDepth = (valueList[0] as? Int) ?: -1
                }
                "includeLibs" -> {
                    includeLibraryCalls = (valueList[0] as? Boolean) ?: false
                }
            }
        }
        return true
    }

    /**
     * 内部类：依赖图切片算法实现
     */
    inner class DependencyGraphSlicer {
        // 模拟一个程序依赖图
        inner class DependencyGraph {
            // 存储语句之间的依赖关系
            // Key: 语句ID，Value: 依赖这个语句的所有语句ID
            private val controlDependencies = HashMap<Int, HashSet<Int>>()
            private val dataDependencies = HashMap<Int, HashSet<Int>>()

            // 存储语句的源码位置信息
            private val statementsInfo = HashMap<Int, Pair<String, Int>>() // (文件, 行号)

            // 添加控制依赖
            fun addControlDependency(from: Int, to: Int) {
                controlDependencies.getOrPut(from) { HashSet() }.add(to)
            }

            // 添加数据依赖
            fun addDataDependency(from: Int, to: Int) {
                dataDependencies.getOrPut(from) { HashSet() }.add(to)
            }

            // 添加语句信息
            fun addStatementInfo(id: Int, file: String, line: Int) {
                statementsInfo[id] = Pair(file, line)
            }

            // 计算依赖切片
            fun computeSlice(
                criteria: List<Int>,
                direction: SliceDirection,
                includeControlDeps: Boolean,
                includeDataDeps: Boolean,
                maxDepth: Int
            ): List<Int> {
                val visited = HashSet<Int>()
                val queue = LinkedList<Pair<Int, Int>>() // (语句ID, 当前深度)

                // 初始化队列
                criteria.forEach { queue.add(Pair(it, 0)) }

                while (queue.isNotEmpty()) {
                    val (current, depth) = queue.poll()

                    // 如果已访问或超出最大深度，跳过
                    if (current in visited || (maxDepth > 0 && depth > maxDepth)) {
                        continue
                    }

                    visited.add(current)

                    // 根据切片方向决定如何遍历依赖
                    if (direction == SliceDirection.BACKWARD || direction == SliceDirection.BIDIRECTIONAL) {
                        // 反向切片：添加当前语句依赖的语句
                        if (includeControlDeps) {
                            controlDependencies.forEach { (from, tos) ->
                                if (current in tos && from !in visited) {
                                    queue.add(Pair(from, depth + 1))
                                }
                            }
                        }

                        if (includeDataDeps) {
                            dataDependencies.forEach { (from, tos) ->
                                if (current in tos && from !in visited) {
                                    queue.add(Pair(from, depth + 1))
                                }
                            }
                        }
                    }

                    if (direction == SliceDirection.FORWARD || direction == SliceDirection.BIDIRECTIONAL) {
                        // 正向切片：添加依赖当前语句的语句
                        if (includeControlDeps && controlDependencies.containsKey(current)) {
                            for (to in controlDependencies[current]!!) {
                                if (to !in visited) {
                                    queue.add(Pair(to, depth + 1))
                                }
                            }
                        }

                        if (includeDataDeps && dataDependencies.containsKey(current)) {
                            for (to in dataDependencies[current]!!) {
                                if (to !in visited) {
                                    queue.add(Pair(to, depth + 1))
                                }
                            }
                        }
                    }
                }

                return visited.toList()
            }

            // 获取语句位置信息
            fun getStatementInfo(id: Int): Pair<String, Int>? {
                return statementsInfo[id]
            }
        }

        // 示例：构建一个依赖图
        private fun buildDependencyGraph(sourcePath: String): DependencyGraph {
            // 实际应用中，这里应该通过静态分析或动态跟踪来构建真实的依赖图
            // 这里我们创建一个示例依赖图用于演示
            val graph = DependencyGraph()

            // 读取源代码并分析依赖关系
            // 这里仅为模拟实现
            try {
                val file = File(sourcePath)
                if (file.exists() && file.isFile) {
                    var lineNo = 1
                    var stmtId = 1

                    file.readLines().forEach { line ->
                        // 简单模拟：将每个非空行视为一个语句
                        if (line.trim().isNotEmpty()) {
                            // 记录语句信息
                            graph.addStatementInfo(stmtId, sourcePath, lineNo)

                            // 模拟一些依赖关系（示例）
                            // 在实际实现中，这些依赖应该通过实际代码分析确定
                            if (stmtId > 1) {
                                // 添加一些示例依赖
                                if (line.contains("if") || line.contains("for") || line.contains("while")) {
                                    // 控制依赖：条件语句影响随后的几条语句
                                    for (i in 1..3) {
                                        if (stmtId + i <= file.readLines().size) {
                                            graph.addControlDependency(stmtId, stmtId + i)
                                        }
                                    }
                                }

                                // 简单启发式：检测变量定义和使用
                                val varPattern = "\\b([a-zA-Z][a-zA-Z0-9_]*)\\s*="
                                val regex = Regex(varPattern)
                                val matches = regex.findAll(line)

                                for (match in matches) {
                                    val varName = match.groupValues[1]
                                    // 向前寻找使用这个变量的语句
                                    for (i in 1..5) {  // 仅查看接下来的5条语句
                                        if (stmtId + i <= file.readLines().size) {
                                            val futureLine = file.readLines()[stmtId + i - 1]
                                            if (futureLine.contains("\\b$varName\\b".toRegex())) {
                                                // 数据依赖：变量定义影响后续使用
                                                graph.addDataDependency(stmtId, stmtId + i)
                                            }
                                        }
                                    }
                                }
                            }

                            stmtId++
                        }
                        lineNo++
                    }
                }
            } catch (e: Exception) {
                println("构建依赖图时发生错误: ${e.message}")
            }

            return graph
        }

        // 执行切片
        fun performSlice(
            sourceFile: String,
            criterionLine: Int,
            direction: SliceDirection,
            includeControlDeps: Boolean,
            includeDataDeps: Boolean,
            maxDepth: Int,
            project: Project
        ): Slice {
            // 1. 构建依赖图
            val graph = buildDependencyGraph(sourceFile)

            // 2. 找到对应的语句ID（简化：假设行号就是ID）
            val criterionId = criterionLine + 1

            // 3. 计算切片
            val sliceIds = graph.computeSlice(
                listOf(criterionId),
                direction,
                includeControlDeps,
                includeDataDeps,
                maxDepth
            )

            // 4. 转换结果为Slice对象
            val statements = ArrayList<Statement>()
            for (id in sliceIds) {
                val info = graph.getStatementInfo(id)
                if (info != null) {
                    val (file, line) = info
                    statements.add(Statement(file, line - 1))  // -1 因为IDE的行号从0开始
                    println("添加到切片: $file:$line")
                }
            }

            return Slice(statements, project)
        }

        // 解析源代码文件获取路径
        fun resolveSourceFilePath(project: Project, className: String): String? {
            // 在真实实现中，应该通过项目的源路径查找给定类名的源文件
            // 这里简化处理，仅做演示
            val baseDir = project.basePath ?: return null

            // 尝试在常见源码目录中查找匹配的文件
            val possiblePaths = arrayOf(
                "src/main/java",
                "src/main/kotlin",
                "src/test/java",
                "src/test/kotlin"
            )

            val fileName = className.substringAfterLast('.') + ".kt"
            val packagePath = className.substringBeforeLast('.').replace('.', File.separatorChar)

            for (srcPath in possiblePaths) {
                val path = File(baseDir, "$srcPath${File.separator}$packagePath${File.separator}$fileName")
                if (path.exists()) {
                    return path.absolutePath
                }
            }

            // 也尝试寻找Java文件
            val javaFileName = className.substringAfterLast('.') + ".java"
            for (srcPath in possiblePaths) {
                val path = File(baseDir, "$srcPath${File.separator}$packagePath${File.separator}$javaFileName")
                if (path.exists()) {
                    return path.absolutePath
                }
            }

            return null
        }
    }

    // 切片器实例
    private val slicer = DependencyGraphSlicer()

    /**
     * 创建程序切片
     */
    fun createSlice(
        project: Project,
        settings: RunnerAndConfigurationSettings?,
        dataContext: DataContext,
        executor: Executor
    ): Slice? {
        val task = object : Task.WithResult<Slice, Exception>(
            project, "执行自定义依赖图切片算法", true
        ) {
            override fun compute(indicator: ProgressIndicator): Slice {
                val outputDirectory = Files.createTempDirectory("custom-slicer-outputs-")
                return getProgramSlice(indicator, outputDirectory)
            }

            private fun getProgramSlice(indicator: ProgressIndicator, outputDirectory: Path): Slice {
                // 1. 获取源文件路径
                indicator.text = "分析源代码"
                val sourceFilePath = slicer.resolveSourceFilePath(project, slicingCriterion!!.clazz)

                if (sourceFilePath == null) {
                    ApplicationManager.getApplication().invokeLater {
                        Messages.showMessageDialog(
                            "无法找到源文件: ${slicingCriterion!!.clazz}",
                            "切片错误",
                            AllIcons.General.WarningDialog
                        )
                    }
                    SelectSlicingCriterionAction.resetSlicingCriterion()
                    return Slice(ArrayList(), project)
                }

                // 2. 构建依赖图并计算切片
                indicator.text = "构建依赖图"
                indicator.text = "计算程序切片"

                return slicer.performSlice(
                    sourceFilePath,
                    slicingCriterion!!.lineNo,
                    sliceDirection,
                    includeControlDependencies,
                    includeDataDependencies,
                    maxDepth,
                    project
                )
            }
        }

        task.queue() // 对于模态任务，同步运行

        // 打印结果
        for (statement in task.result.statements) {
            println("切片结果包含: ${statement.clazz}:${statement.lineNo+1}")
        }

        return task.result
    }

    companion object {
        // 可以在这里添加一些辅助方法或常量
        const val ALGORITHM_VERSION = "1.0.0"

        fun getAlgorithmDescription(): String {
            return "基于程序依赖图的切片算法，支持正向、反向和双向切片，" +
                    "可配置控制依赖和数据依赖的包含，以及切片深度限制。"
        }
    }
}