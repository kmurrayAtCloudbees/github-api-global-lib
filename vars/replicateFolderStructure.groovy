// vars/replicateFolderStructure.groovy
// CloudBees CI Migration - Folder Structure Replication
// Ensures all folders exist on all target controllers

import jenkins.model.Jenkins
import hudson.model.*
import com.cloudbees.hudson.plugins.folder.Folder

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

    script {
        def discoverAllFolders = {
            def VERBOSE = config.verbose
            def allFolders = []

            if (VERBOSE) {
                println "=== CloudBees CI Folder Structure Discovery ==="
                println "Parent Folder: ${config.parentFolderPath}"
                println "Target Controllers: ${config.targetControllers.join(', ')}"
                println "=" * 60
            }

            def analyzeFolderStructure
            analyzeFolderStructure = { folder, currentPath ->
                try {
                    def folderPath = currentPath ? "${currentPath}/${folder.name}" : folder.name
                    
                    // Add this folder to the list
                    allFolders << folderPath
                    
                    if (VERBOSE) {
                        println "Discovered folder: ${folderPath}"
                    }

                    // Recursively process subfolders
                    folder.getItems().each { item ->
                        if (item instanceof Folder) {
                            analyzeFolderStructure(item, folderPath)
                        }
                    }

                } catch (Exception e) {
                    if (VERBOSE) println "ERROR analyzing folder ${folder.name}: ${e.message}"
                }
            }

            def jenkins = Jenkins.getInstance()
            def analyzeAllRoot = (config.parentFolderPath == "ROOT" || config.parentFolderPath == "")

            if (analyzeAllRoot) {
                if (VERBOSE) println "Discovering ALL Jenkins root folders"

                jenkins.getItems().each { item ->
                    try {
                        if (item instanceof Folder) {
                            analyzeFolderStructure(item, "")
                        }
                    } catch (Exception e) {
                        if (VERBOSE) println "ERROR processing root folder ${item.name}: ${e.message}"
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
                    analyzeFolderStructure(parentFolder, "")
                } else {
                    error("Parent folder '${config.parentFolderPath}' not found!")
                }
            }

            return allFolders.sort()
        }

        def discoveredFolders = discoverAllFolders()

        // Generate folder replication commands for each target controller
        def folderCommands = []
        def controllers = config.targetControllers

        discoveredFolders.each { folderPath ->
            controllers.each { targetController ->
                folderCommands << "${folderPath},${targetController}"
            }
        }

        results = [
            discoveredFolders: discoveredFolders,
            folderCommands: folderCommands,
            stats: [
                totalFolders: discoveredFolders.size(),
                targetControllers: controllers.size(),
                totalCommands: folderCommands.size()
            ]
        ]

        if (config.verbose) {
            println "\n=== FOLDER STRUCTURE REPLICATION SUMMARY ==="
            println "Total Folders Discovered: ${discoveredFolders.size()}"
            println "Target Controllers: ${controllers.size()}"
            println "Total Replication Commands: ${folderCommands.size()}"
            println ""
            println "Discovered Folders:"
            discoveredFolders.each { println "  - ${it}" }
            println ""
            println "Replication Commands:"
            folderCommands.each { println "  ${it}" }
        }

        if (!config.dryRun) {
            writeFile file: "${config.outputDir}/all-folders.txt", text: discoveredFolders.join('\n')
            writeFile file: "${config.outputDir}/folder-replication.csv", text: "folder_path,target_controller\n" + folderCommands.join('\n')

            println "\n=== FOLDER STRUCTURE FILES GENERATED ==="
            println "- all-folders.txt (${discoveredFolders.size()} folders)"
            println "- folder-replication.csv (${folderCommands.size()} commands)"
        } else {
            println "\n=== DRY RUN MODE — Folder structure files would be generated ==="
            println "Output directory: ${config.outputDir}"
        }
    }

    return results
}