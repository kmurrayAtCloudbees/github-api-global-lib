def call(Map config=[:]) {
    sh "echo ${config.name} is starting a CD pipeline"
    cloudBeesFlowRunPipeline pipelineName:'ktest',configuration:'TITrainingServer', projectName: 'Training_kmurray'
}

