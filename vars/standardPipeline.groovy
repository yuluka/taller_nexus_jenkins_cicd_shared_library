import org.iaslab.cicd.PipelineConfig

def call(Map rawConfig = [:]) {
    PipelineConfig cfg = new PipelineConfig(rawConfig)
    String deployedImage = ""
    boolean deployAttempted = false

    pipeline {
        agent any

        options {
            timeout(time: 30, unit: 'MINUTES')
            timestamps()
        }

        stages {
            stage('Checkout SCM') {
                steps {
                    echo ">> Clonando rama del microservicio..."
                    checkout scm
                }
            }

            stage('Compile & Test') {
                steps {
                    echo ">> Compilando con Maven y ejecutando pruebas unitarias..."
                    sh "mvn -B clean test"
                }
            }

            stage('Package & Publish to Nexus') {
                steps {
                    echo ">> Empaquetando y publicando en Nexus..."
                    script {
                        def pubResult = publishToNexus(
                            serviceName: cfg.serviceName,
                            nexusHost: cfg.nexusHost,
                            dockerPort: cfg.dockerPort,
                            publishJar: cfg.publishJar,
                            publishDocker: cfg.publishDocker
                        )
                        deployedImage = pubResult.fullImageName
                    }
                }
            }

            stage('Deploy to QA') {
                steps {
                    echo ">> Desplegando en servidor QA: ${cfg.deployTarget}..."
                    script {
                        deployAttempted = true
                        withCredentials([sshUserPrivateKey(credentialsId: 'ssh-deploy-qa', keyFileVariable: 'SSH_KEY', usernameVariable: 'SSH_USER')]) {
                            sh """
                                ssh -o StrictHostKeyChecking=no -i "\$SSH_KEY" "\$SSH_USER@${cfg.deployTarget}" '
                                    set -e
                                    echo "Descargando imagen: ${deployedImage}..."
                                    docker pull ${deployedImage}

                                    # Respaldar versión previa para rollback
                                    PREV_IMAGE=\$(docker inspect --format="{{.Config.Image}}" ${cfg.serviceName} 2>/dev/null || echo "")
                                    echo "\$PREV_IMAGE" > /tmp/${cfg.serviceName}_prev_image.txt

                                    docker stop ${cfg.serviceName} 2>/dev/null || true
                                    docker rm ${cfg.serviceName} 2>/dev/null || true

                                    # Iniciar contenedor
                                    docker run -d --name ${cfg.serviceName} -p 9021:8080 ${deployedImage}
                                '
                            """
                        }
                    }
                }
            }

            stage('Healthcheck & Smoke Test') {
                steps {
                    echo ">> Ejecutando Smoke Test contra ${cfg.healthEndpoint}..."
                    script {
                        sh """
                            ENDPOINT="http://${cfg.deployTarget}:9021${cfg.healthEndpoint}"
                            echo "Validando en: \$ENDPOINT"
                            SUCCESS=0
                            for i in 1 2 3 4 5; do
                                echo "Intento \$i de 5..."
                                STATUS=\$(curl -s -o /dev/null -w "%{http_code}" "\$ENDPOINT" || echo "000")
                                if [ "\$STATUS" -ge 200 ] && [ "\$STATUS" -lt 400 ]; then
                                    echo ">> Smoke Test Exitoso! Código HTTP: \$STATUS"
                                    SUCCESS=1
                                    break
                                fi
                                sleep 5
                            done

                            if [ \$SUCCESS -ne 1 ]; then
                                echo ">> ERROR: Smoke Test falló tras 5 reintentos."
                                exit 1
                            fi
                        """
                    }
                }
            }
        }

        post {
            failure {
                script {
                    if (deployAttempted) {
                        echo ">> [ALERTA] Fallo en despliegue. Ejecutando Rollback automático en ${cfg.deployTarget}..."
                        withCredentials([sshUserPrivateKey(credentialsId: 'ssh-deploy-qa', keyFileVariable: 'SSH_KEY', usernameVariable: 'SSH_USER')]) {
                            sh """
                                ssh -o StrictHostKeyChecking=no -i "\$SSH_KEY" "\$SSH_USER@${cfg.deployTarget}" '
                                    if [ -f /tmp/${cfg.serviceName}_prev_image.txt ]; then
                                        PREV_IMG=\$(cat /tmp/${cfg.serviceName}_prev_image.txt)
                                        if [ -n "\$PREV_IMG" ]; then
                                            echo "Restaurando versión anterior: \$PREV_IMG..."
                                            docker stop ${cfg.serviceName} 2>/dev/null || true
                                            docker rm ${cfg.serviceName} 2>/dev/null || true
                                            docker run -d --name ${cfg.serviceName} -p 9021:8080 "\$PREV_IMG"
                                            echo ">> Rollback completado exitosamente."
                                        fi
                                    fi
                                '
                            """
                        }
                    }
                }
            }
        }
    }
}
