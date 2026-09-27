def call(Map params = [:]) {
    String serviceName = params.serviceName
    String nexusHost = params.nexusHost
    String dockerPort = params.dockerPort ?: '9080'
    boolean publishJar = params.publishJar != null ? params.publishJar : true
    boolean publishDocker = params.publishDocker != null ? params.publishDocker : true

    // Tag inmutable: ${BUILD_NUMBER}-${GIT_COMMIT[0..7]}
    String commitHash = env.GIT_COMMIT ? env.GIT_COMMIT.take(8) : 'unknown'
    String imageTag = "${env.BUILD_NUMBER}-${commitHash}"
    String registryUrl = "${nexusHost}:${dockerPort}"
    String fullImageName = "${registryUrl}/${serviceName}:${imageTag}"

    // 1. Publicar JAR en Nexus (maven-releases)
    if (publishJar) {
        echo ">> Publicando JAR en Sonatype Nexus (maven-releases)..."
        withCredentials([usernamePassword(credentialsId: 'nexus-credentials', usernameVariable: 'NEXUS_USER', passwordVariable: 'NEXUS_PASS')]) {
            sh """
                mvn -B clean deploy -DskipTests \
                    -DaltDeploymentRepository=nexus::default::http://${NEXUS_USER}:${NEXUS_PASS}@${nexusHost}:8081/repository/maven-releases/
            """
        }
    }

    // 2. Construir y Publicar Imagen Docker en Nexus Registry (:9080)
    if (publishDocker) {
        echo ">> Construyendo imagen Docker: ${fullImageName}..."
        sh "docker build -t ${fullImageName} ."

        echo ">> Publicando en Nexus Docker Registry..."
        withCredentials([usernamePassword(credentialsId: 'nexus-credentials', usernameVariable: 'NEXUS_USER', passwordVariable: 'NEXUS_PASS')]) {
            sh """
                echo "\$NEXUS_PASS" | docker login ${registryUrl} -u "\$NEXUS_USER" --password-stdin
                docker push ${fullImageName}
                docker logout ${registryUrl}
            """
        }
    }

    return [imageTag: imageTag, fullImageName: fullImageName]
}
