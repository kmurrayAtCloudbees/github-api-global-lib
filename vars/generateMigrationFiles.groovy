// vars/generateMigrationFiles.groovy
// CloudBees CI Migration - Job Classification and File Generation
// Shared Library Step for generating migration input files

def call(Map config = [:]) {
    // Default configuration
    def defaultConfig = [
        parentFolderPath: "",  // "" or "ROOT" for all folders
        activityThresholdDays: 180,
        maxJobsPerFolder: 5000,
        migrationDepth: 3,  // Depth level for migration grouping (e.g., org/team/project = 3)
        excludePatterns: [],  // Additional exclude patterns
        targetControllers: ["controllerTarget"], // List of target controller names
        outputDir: ".",  // Directory to write files to
        verbose: true,
        dryRun: false
    ]
    
    // Merge user config with defaults
    config = defaultConfig + config
    
    def results = [:]
    
    script {
        // Import required classes
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
            
            // Date calculation
            def sixMonthsAgo = new Date(System.currentTimeMillis() - TimeUnit.DAYS.toMillis(ACTIVITY_THRESHOLD_DAYS))
            def dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss")
            
            if (VERBOSE) {
                println "=== CloudBees CI Migration File Generator ==="
                println "Parent Folder: ${config.parentFolderPath}"
                println "Activity Threshold: ${ACTIVITY_THRESHOLD_DAYS} days"
                println "Migration Depth: ${MIGRATION_DEPTH}"
                println "Target Controllers: ${config.targetControllers.join(', ')}"
                println "=" * 60
            }
            
            // Results containers
            def folderClassification = [:]  // folder -> classification (active/inactive)
            def folderJobCounts = [:]       // folder -> job count
            def stats = [totalJobs: 0, activeFolders: 0, inactiveFolders: 0, foldersAnalyzed: 0]
            
            // Helper function to get migration folder path at specified depth
            def getMigrationFolder(String fullPath) {
                def parts = fullPath.split('/')
                if (parts.length < MIGRATION_DEPTH) {
                    return fullPath  // Return as-is if not deep enough
                }
                return parts[0..<MIGRATION_DEPTH].join('/')
            }
            
            // Helper function to get job activity status
            def getJobActivityStatus = { job ->
                try {
                    if (!job.isBuildable()) {
                        return "DISABLED"
                    }
                    def lastBuild = job.getLastBuild()
                    if (!lastBuild) {
                        return "NEVER_RUN"
                    }
                    def lastBuildDate = new Date(lastBuild.getTimeInMillis())
                    return lastBuildDate.after(sixMonthsAgo) ? "ACTIVE" : "STALE"
                } catch (Exception e) {
                    return "ERROR"
                }
            }
            
            // Recursive folder analysis
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
                    
                    // Analyze jobs in this folder
                    def hasActiveJobs = false
                    def totalJobs = jobsInFolder.size()
                    
                    jobsInFolder.each { job ->
                        stats.totalJobs++
                        def status = getJobActivityStatus(job)
                        if (status == "ACTIVE") {
                            hasActiveJobs = true
                        }
                    }
                    
                    // Update folder classification at migration depth
                    if (totalJobs > 0) {
                        if (!folderJobCounts.containsKey(migrationFolder)) {
                            folderJobCounts[migrationFolder] = 0
                        }
                        folderJobCounts[migrationFolder] += totalJobs
                        
                        // If any jobs in this path are active, mark the migration folder as active
                        if (hasActiveJobs) {
                            folderClassification[migrationFolder] = "ACTIVE"
                        } else if (!folderClassification.containsKey(migrationFolder)) {
                            // Only set to inactive if not already marked active
                            folderClassification[migrationFolder] = "INACTIVE"
                        }
                    }
                    
                    if (VERBOSE && totalJobs > 0) {
                        println "Folder: ${folderPath} -> Migration Group: ${migrationFolder} (${totalJobs} jobs, hasActive: ${hasActiveJobs})"
                    }
                    
                    // Recursively analyze subfolders
                    subfoldersInFolder.each { subfolder ->
                        analyzeFolder(subfolder, folderPath)
                    }
                    
                } catch (Exception e) {
                    if (VERBOSE) println "ERROR analyzing folder ${folder.name}: ${e.message}"
                }
            }
            
            // Start analysis
            def jenkins = Jenkins.getInstance()
            def analyzeAllRoot = (config.parentFolderPath == "ROOT" || config.parentFolderPath == "")
            
            if (analyzeAllRoot) {
                if (VERBOSE) println "Analyzing ALL Jenkins root folders and jobs"
                
                jenkins.getItems().each { item ->
                    try {
                        if (item instanceof Folder) {
                            analyzeFolder(item, "")
                        } else if (item instanceof Job) {
                            // Handle root-level jobs
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
                // Find specific parent folder
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
            
            // Calculate final statistics
            folderClassification.each { folder, classification ->
                stats.foldersAnalyzed++
                if (classification == "ACTIVE") {
                    stats.activeFolders++
                } else {
                    stats.inactiveFolders++
                }
            }
            
            return [
                folderClassification: folderClassification,
                folderJobCounts: folderJobCounts,
                stats: stats
            ]
        }
        
        // Execute the analysis
        def analysisResults = analyzeJenkinsJobs()
        
        // Generate file contents
        def activeFolders = []
        def inactiveFolders = []
        def combinedFolders = []
        
        analysisResults.folderClassification.each { folder, classification ->
            if (classification == "ACTIVE") {
                activeFolders << folder
            } else {
                inactiveFolders << folder
            }
            combinedFolders << folder
        }
        
        // Sort folders for consistent output
        activeFolders = activeFolders.sort()
        inactiveFolders = inactiveFolders.sort()
        combinedFolders = combinedFolders.sort()
        
        // Generate assignments.csv for load balancing across target controllers
        def assignments = []
        def targetControllers = config.targetControllers
        def controllerIndex = 0
        
        combinedFolders.each { folder ->
            def targetController = targetControllers[controllerIndex % targetControllers.size()]
            def jobCount = analysisResults.folderJobCounts[folder] ?: 0
            assignments << "${folder},${targetController},${jobCount}"
            controllerIndex++
        }
        
        // Generate excludes.txt (static patterns + user-defined)
        def excludePatterns = [
            '**/build.xml.tmp',
            '**/workflow/*',
            '**/builds/*/log',
            '**/*.log'
        ] + config.excludePatterns
        
        // Prepare results
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
        
        // Write files if not in dry run mode
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
            println "\n=== DRY RUN MODE - Files would be generated ==="
            println "Output directory: ${config.outputDir}"
        }
    }
    
    return results
}