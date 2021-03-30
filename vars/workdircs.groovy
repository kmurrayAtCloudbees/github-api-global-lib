def call(Map config=[:]) {
    echo "Path: $PWD"
    echo "workdir: ${config.workdir}"
    dir ("${config.workdir}") {
          sh """
	     docker ps
             pwd
             cp -R ${config.workdir}/src/main/resources/certs ${config.workdir}/.
             buildah bud .
	  """
    }
    sh "echo ${config.workdir} is in the lib"
    sh "echo Docker, umm I mean BUILDAH, build complete!"
    }
