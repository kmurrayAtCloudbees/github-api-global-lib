def call(Map config=[:]) {
    sh "echo ${config.name} is starting a CD pipeline"
    cloudBeesFlowRunPipeline pipelineName:"${config.pipelineName}",configuration:"${config.configuration}", projectName:"${config.projectName}"
}

