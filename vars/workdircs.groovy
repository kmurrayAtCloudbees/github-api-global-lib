def call(Map config=[:]) {
    echo "Path: $PWD"
    echo "workdir: ${config.workdir}"
    dir ("${config.workdir}") {
          sh """
             pwd
             cp -R ${config.workdir}/src/main/resources/certs ${config.workdir}/.
	  """
    }
    sh "echo ${config.workdir} is in the lib"
    sh "echo Docker, umm I mean BUILDAH, build complete!"
    }
