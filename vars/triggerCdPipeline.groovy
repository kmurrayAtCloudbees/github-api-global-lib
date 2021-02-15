def call(String pipelineName, String configuration, String projectName) {
  sh "cloudBeesFlowRunPipeline pipelineName: "${pipelineName"}, configuration: "${configuration}", projectName: "${projectName}""
}

