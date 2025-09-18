def call(Map config = [:]) {
    def folderSet = [] as Set

    def processItem
    processItem = { item, currentPath ->
        def itemPath = currentPath ? "${currentPath}/${item.name}" : item.name

        if (item instanceof com.cloudbees.hudson.plugins.folder.Folder) {
            folderSet << itemPath
            item.getItems().each { child ->
                processItem(child, itemPath)
            }
        } else if (item instanceof hudson.model.Job) {
            def parts = itemPath.split('/')
            def current = ''
            parts.init().each { part ->
                current = current ? "${current}/${part}" : part
                folderSet << current
            }
        }
    }

    def jenkins = jenkins.model.Jenkins.get()
    jenkins.getItems().each { item -> processItem(item, '') }

    def folders = folderSet.sort()
    def outputFile = config.outputPath ?: 'folders.txt'
    def text = folders.join('\n')
    writeFile(file: outputFile, text: text)

    println "Generated ${folders.size()} folders in ${outputFile}"

    return folders
}