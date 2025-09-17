// vars/replicateFolderStructure.groovy
// CloudBees CI Migration - Folder Structure Replication
// Ensures all folders exist on all target controllers

def call(Map config = [:]) {
    // Default configuration
    def defaultConfig = [
        parentFolderPath: "",  // "" or "ROOT" for all folders
        targetControllers: ["controllerSource", "controllerTarget"],
        outputDir: ".",
        verbose: true,
        dryRun: false
    ]

    config = defaultConfig + config
    def results = [:]

    // Use script block for Jenkins operations
    def discoverAllFolders = {
        def VERBOSE = config.verbose
        def allFolders = []

        if (VERBOSE) {
            echo "=== CloudBees CI Folder Structure Discovery ==="
            echo "Parent Folder: ${config.parentFolderPath}"
            echo "Target Controllers: ${config.targetControllers.join(', ')}"
            echo "=" * 60
        }

        // Define recursive function outside of script block to avoid serialization issues
        def analyzeFolderStructure
        analyzeFolderStructure = { folder, currentPath ->
            try {
                def folderPath = currentPath ? "${currentPath}/${folder.name}" : folder.name
                
                // Add this folder to the list
                allFolders.add(folderPath)
                
                if (VERBOSE) {
                    echo "Discovered folder: ${folderPath}"
                }

                // Recursively process subfolders - use Jenkins-safe iteration
                def items = folder.getItems()
                for (def item : items) {
                    if (item.getClass().getName().contains("cloudbees.hudson.plugins.folder.Folder")) {
                        analyzeFolderStructure(item, folderPath)
                    }
                }

            } catch (Exception e) {
                if (VERBOSE) echo "ERROR analyzing folder ${folder.name}: ${e.message}"
            }
        }

        // Use script block for Jenkins API access
        script {
            def jenkins = Jenkins.getInstance()
            def analyzeAllRoot = (config.parentFolderPath == "ROOT" || config.parentFolderPath == "")

            if (analyzeAllRoot) {
                if (VERBOSE) echo "Discovering ALL Jenkins root folders"

                def rootItems = jenkins.getItems()
                for (def item : rootItems) {
                    try {
                        if (item.getClass().getName().contains("cloudbees.hudson.plugins.folder.Folder")) {
                            analyzeFolderStructure(item, "")
                        }
                    } catch (Exception e) {
                        if (VERBOSE) echo "ERROR processing root folder ${item.name}: ${e.message}"
                    }
                }
            } else {
                def findFolderByPath = { String folderPath ->
                    def pathParts = folderPath.split('/')
                    def currentItem = jenkins

                    for (def part : pathParts) {
                        if (!part || part.isEmpty()) continue
                        def found = false
                        def currentItems = currentItem.getItems()
                        for (def item : currentItems) {
                            if (item.name == part && item.getClass().getName().contains("cloudbees.hudson.plugins.folder.Folder")) {
                                currentItem = item
                                found = true
                                break
                            }
                        }
                        if (!found) {
                            if (VERBOSE) echo "ERROR: Folder '${part}' not found in path '${folderPath}'"
                            return null
                        }
                    }
                    return currentItem.getClass().getName().contains("cloudbees.hudson.plugins.folder.Folder") ? currentItem : null
                }

                def parentFolder = findFolderByPath(config.parentFolderPath)
                if (parentFolder) {
                    if (VERBOSE) echo "Found parent folder: ${config.parentFolderPath}"
                    analyzeFolderStructure(parentFolder, "")
                } else {
                    error("Parent folder '${config.parentFolderPath}' not found!")
                }
            }
        }

        // Sort folders using Jenkins-safe method
        Collections.sort(allFolders)
        return allFolders
    }

    def discoveredFolders = discoverAllFolders()

    // Generate folder replication commands for each target controller
    def folderCommands = []
    def controllers = config.targetControllers

    for (def folderPath : discoveredFolders) {
        for (def targetController : controllers) {
            folderCommands.add("${folderPath},${targetController}")
        }
    }

    results = [
        discoveredFolders: discoveredFolders,
        folderCommands: folderCommands,
        stats: [
            totalFolders: discoveredFolders?.size() ?: 0,
            targetControllers: controllers?.size() ?: 0,
            totalCommands: folderCommands?.size() ?: 0
        ]
    ]

    if (config.verbose) {
        echo "\n=== FOLDER STRUCTURE REPLICATION SUMMARY ==="
        echo "Total Folders Discovered: ${discoveredFolders?.size() ?: 0}"
        echo "Target Controllers: ${controllers?.size() ?: 0}"
        echo "Total Replication Commands: ${folderCommands?.size() ?: 0}"
        echo ""
        
        if (discoveredFolders && discoveredFolders.size() > 0) {
            echo "Discovered Folders:"
            for (def folder : discoveredFolders) {
                echo "  - ${folder}"
            }
        }
        
        echo ""
        if (folderCommands && folderCommands.size() > 0) {
            echo "Replication Commands:"
            // Limit output to prevent overwhelming logs - Jenkins-safe way
            def maxDisplay = 10
            def commandCount = 0
            for (def command : folderCommands) {
                if (commandCount < maxDisplay) {
                    echo "  ${command}"
                    commandCount++
                } else {
                    echo "  ... and ${folderCommands.size() - maxDisplay} more commands"
                    break
                }
            }
        }
    }

    if (!config.dryRun) {
        // Generate all-folders.txt
        def foldersText = ""
        if (discoveredFolders && discoveredFolders.size() > 0) {
            for (def folder : discoveredFolders) {
                foldersText += folder + "\n"
            }
        }
        writeFile file: "${config.outputDir}/all-folders.txt", text: foldersText

        // Generate folder-replication.csv
        def csvText = "folder_path,target_controller\n"
        if (folderCommands && folderCommands.size() > 0) {
            for (def command : folderCommands) {
                csvText += command + "\n"
            }
        }
        writeFile file: "${config.outputDir}/folder-replication.csv", text: csvText

        echo "\n=== FOLDER STRUCTURE FILES GENERATED ==="
        echo "- all-folders.txt (${discoveredFolders?.size() ?: 0} folders)"
        echo "- folder-replication.csv (${folderCommands?.size() ?: 0} commands)"
    } else {
        echo "\n=== DRY RUN MODE — Folder structure files would be generated ==="
        echo "Output directory: ${config.outputDir}"
    }

    return results
}