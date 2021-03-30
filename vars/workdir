def call(String workdir) {

// This build currently puts the image in the local buildah repo.
// The image will be build to the nexus repo, when that is made available.
    echo "Path: $PWD"
    echo "workdir: ${workdir}"
    dir(${workdir}) {
        sh """
            sudo docker ps
            pwd
            cp -R ${workdir}/src/main/resources/certs ${workdir}/.
            sudo buildah bud .
        """
    }
    echo "Docker, umm I mean BUILDAH, build complete!"
}
