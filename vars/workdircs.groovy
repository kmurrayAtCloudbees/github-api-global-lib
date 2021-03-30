//def call(String workdir) {
def call(Map config=[:]) {
// This build currently puts the image in the local buildah repo.
// The image will be build to the nexus repo, when that is made available.
    echo "Path: $PWD"
    echo "workdir: ${config.workdir}"
    dir(${config.workdir}) {
//        sh """
          sh  "echo ${config.workdir} is in the lib"
//            sudo docker ps
//            pwd
//            cp -R ${config.workdir}/src/main/resources/certs ${config.workdir}/.
//            sudo buildah bud .
//        """
    }
    sh "echo Docker, umm I mean BUILDAH, build complete!"
    }
