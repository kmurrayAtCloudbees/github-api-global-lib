// CloudBees CI Migration - Job Classification and File Generation
// Shared Library Step for generating migration input files

def call(Map config = [:]) {
    // Default configuration
    def defaultConfig = [
        parentFolderPath: "",  // "" or "ROOT" for all folders
        activityThresholdDays: 180,
        maxJobsPerFolder: 5000,
        migrationDepth: 3,  // Depth level from RIGHT (e.g., org/team/project = last 3 dirs before job)
        excludePatterns: [],
        targetControllers: ["controllerSource", "controllerTarget"], // Your defaults
        outputDir: ".",
        verbose: true,
        dryRun: false
    ]

    // Merge with user-supplied config
    config = defaultConfig + config

    def results = [:]

    script {
        @NonCPS
        def analyzeJenkinsJobs() {
            import jenkins.model.Jenkins
            import hudson.model.*
            import com.cloudbees.hudson.plugins.folder.Folder
            import java.text.SimpleDateFormat
            import java.util.concurrent.TimeUnit

            def VERBOSE = config.verbose
            def ACTIVITY_THRESHOLD_DAYS = config.activityThresholdDays
            def MIGRATION_DEPTH = config.migrationDepth

            def sixMonthsAgo = new Date(System.currentTimeMillis() - TimeUnit.DAYS.toMillis(ACTIVITY_THRESHOLD_DAYS))
            def dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss")

            if (VERBOSE) {
                println "=== CloudBees CI Migration File Generator ==="
                println "Parent Folder: ${config.parentFolderPath}"
                println "Activity Threshold: ${ACTIVITY_THRESHOLD_DAYS} days"
                println "Migration Depth (from right): ${MIGRATION_DEPTH}"
                println "Target Controllers: ${config.targetControllers.join(', ')}"
                println "=" * 60
            }

            def folderClassification = [:]
            def folderJobCounts = [:]
            def stats = [totalJobs: 0, activeFolders: 0, inactiveFolders: 0, foldersAnalyzed: 0]

            def getMigrationFolder = { String fullPath ->
                def parts = fullPath.tokenize('/')

                if (parts.size() >= MIGRATION_DEPTH + 1) {
                    def start = parts.size() - MIGRATION_DEPTH - 1
                    def end = parts.size() - 1
                    return parts[start..<end].join('/')
                } else {
                    return "[UNRESOLVED] ${fullPath}"
                }
            }

            def getJobActivityStatus = { job ->
                try {
                    if (!job.isBuildable()) return "DISABLED"
                    def lastBuild = job.getLastBuild()
                    if (!lastBuild) return "NEVER_RUN"
                    def lastBuildDate = new Date(lastBuild.getTimeInMillis())
                    return lastBuildDate.after(sixMonthsAgo) ? "ACTIVE" : "STALE"
                } catch (Exception e) {
                    return "ERROR"
                }
            }

            def analyzeFolder
            analyzeFolder = { folder, currentPath ->
                try {
                    def folderPath = currentPath ? "${currentPath}/${folder.name}" : folder.name
                    def migrationFolder = getMigrationFolder(folderPath)

                    def jobsInFolder = []
                    def subfoldersInFolder = []

                    folder.getItems().each { item ->
                        if (item instanceof Folder) {
                            subfoldersInFolder << item
                        } else if (item instanceof Job) {
                            jobsInFolder << item
                        }
                    }

                    def hasActiveJobs = false
                    def totalJobs = jobsInFolder.size()

                    jobsInFolder.each { job ->
                        stats.totalJobs++
                        def status = getJobActivityStatus(job)
                        if (status == "ACTIVE") {
                            hasActiveJobs = true
                        }
                    }

                    if (totalJobs > 0) {
                        if (!folderJobCounts.containsKey(migrationFolder)) {
                            folderJobCounts[migrationFolder] = 0
                        }
                        folderJobCounts[migrationFolder] += totalJobs

                        if (hasActiveJobs) {
                            folderClassification[migrationFolder] = "ACTIVE"
                        } else if (!folderClassification.containsKey(migrationFolder)) {
                            folderClassification[migrationFolder] = "INACTIVE"
                        }
                    }

                    if (VERBOSE && totalJobs > 0) {
                        println "Folder: ${folderPath} → Migration Unit: ${migrationFolder} (${totalJobs} jobs, hasActive: ${hasActiveJobs})"
                    }

                    subfoldersInFolder.each { subfolder ->
                        analyzeFolder(subfolder, folderPath)
                    }

                } catch (Exception e) {
                    if (VERBOSE) println "ERROR analyzing folder ${folder.name}: ${e.message}"
                }
            }

            def jenkins = Jenkins.getInstance()
            def analyzeAllRoot = (config.parentFolderPath == "ROOT" || config.parentFolderPath == "")

            if (analyzeAllRoot) {
                if (VERBOSE) println "Analyzing ALL Jenkins root folders and jobs"

                jenkins.getItems().each { item ->
                    try {
                        if (item instanceof Folder) {
                            analyzeFolder(item, "")
                        } else if (item instanceof Job) {
                            stats.totalJobs++
                            def status = getJobActivityStatus(item)
                            def migrationFolder = "ROOT"

                            if (!folderJobCounts.containsKey(migrationFolder)) {
                                folderJobCounts[migrationFolder] = 0
                            }
                            folderJobCounts[migrationFolder]++

                            if (status == "ACTIVE") {
                                folderClassification[migrationFolder] = "ACTIVE"
                            } else if (!folderClassification.containsKey(migrationFolder)) {
                                folderClassification[migrationFolder] = "INACTIVE"
                            }
                        }
                    } catch (Exception e) {
                        if (VERBOSE) println "ERROR processing root item ${item.name}: ${e.message}"
                    }
                }
            } else {
                def findFolderByPath = { String folderPath ->
                    def pathParts = folderPath.split('/')
                    def currentItem = jenkins

                    for (String part : pathParts) {
                        if (part.isEmpty()) continue
                        def found = false
                        for (def item : currentItem.getItems()) {
                            if (item.name == part && item instanceof Folder) {
                                currentItem = item
                                found = true
                                break
                            }
                        }
                        if (!found) {
                            if (VERBOSE) println "ERROR: Folder '${part}' not found in path '${folderPath}'"
                            return null
                        }
                    }

                    return currentItem instanceof Folder ? currentItem : null
                }

                def parentFolder = findFolderByPath(config.parentFolderPath)
                if (parentFolder) {
                    if (VERBOSE) println "Found parent folder: ${config.parentFolderPath}"
                    analyzeFolder(parentFolder, "")
                } else {
                    error("Parent folder '${config.parentFolderPath}' not found!")
                }
            }

            folderClassification.each { folder, classification ->
                stats.foldersAnalyzed++
                if (classification == "ACTIVE") stats.activeFolders++
                else stats.inactiveFolders++
            }

            return [
                folderClassification: folderClassification,
                folderJobCounts: folderJobCounts,
                stats: stats
            ]
        }

        def analysisResults = analyzeJenkinsJobs()

        def activeFolders = []
        def inactiveFolders = []
        def combinedFolders = []

        analysisResults.folderClassification.each { folder, classification ->
            if (classification == "ACTIVE") activeFolders << folder
            else inactiveFolders << folder
            combinedFolders << folder
        }

        activeFolders = activeFolders.sort()
        inactiveFolders = inactiveFolders.sort()
        combinedFolders = combinedFolders.sort()

        def assignments = []
        def controllerIndex = 0
        def controllers = config.targetControllers

        combinedFolders.each { folder ->
            def target = controllers[controllerIndex % controllers.size()]
            def jobCount = analysisResults.folderJobCounts[folder] ?: 0
            assignments << "${folder},${target},${jobCount}"
            controllerIndex++
        }

        def excludePatterns = [
            '**/build.xml.tmp',
            '**/workflow/*',
            '**/builds/*/log',
            '**/*.log'
        ] + config.excludePatterns

        results = [
            activeFolders: activeFolders,
            inactiveFolders: inactiveFolders,
            combinedFolders: combinedFolders,
            assignments: assignments,
            excludePatterns: excludePatterns,
            stats: analysisResults.stats,
            analysis: analysisResults
        ]

        if (config.verbose) {
            println "\n=== MIGRATION FILES GENERATION SUMMARY ==="
            println "Total Jobs Analyzed: ${results.stats.totalJobs}"
            println "Active Folders: ${results.stats.activeFolders}"
            println "Inactive Folders: ${results.stats.inactiveFolders}"
            println "Total Migration Folders: ${results.stats.foldersAnalyzed}"
            println ""
            println "Active Folders (${activeFolders.size()}):"
            activeFolders.each { println "  - ${it}" }
            println ""
            println "Inactive Folders (${inactiveFolders.size()}):"
            inactiveFolders.each { println "  - ${it}" }
            println ""
            println "Target Controller Assignments:"
            assignments.each { println "  ${it}" }
        }

        if (!config.dryRun) {
            writeFile file: "${config.outputDir}/active.txt", text: activeFolders.join('\n')
            writeFile file: "${config.outputDir}/inactive.txt", text: inactiveFolders.join('\n')
            writeFile file: "${config.outputDir}/combined.txt", text: combinedFolders.join('\n')
            writeFile file: "${config.outputDir}/assignments.csv", text: "folder,target_controller,job_count\n" + assignments.join('\n')
            writeFile file: "${config.outputDir}/excludes.txt", text: excludePatterns.join('\n')

            println "\n=== FILES GENERATED ==="
            println "- active.txt (${activeFolders.size()} folders)"
            println "- inactive.txt (${inactiveFolders.size()} folders)"
            println "- combined.txt (${combinedFolders.size()} folders)"
            println "- assignments.csv (${assignments.size()} assignments)"
            println "- excludes.txt (${excludePatterns.size()} patterns)"
        } else {
            println "\n=== DRY RUN MODE — Files would be generated ==="
            println "Output directory: ${config.outputDir}"
        }
    }

    return results
}
