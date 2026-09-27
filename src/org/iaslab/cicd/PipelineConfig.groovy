package org.iaslab.cicd

class PipelineConfig implements Serializable {
    String serviceName
    String buildType = 'maven'
    String jdkVersion = '17'
    boolean publishJar = true
    boolean publishDocker = true
    String nexusHost
    String dockerPort = '9080'
    String deployTarget
    String healthEndpoint = '/api/products'

    PipelineConfig(Map config) {
        if (!config.serviceName) {
            throw new IllegalArgumentException("El parámetro 'serviceName' es obligatorio.")
        }
        if (!config.nexusHost) {
            throw new IllegalArgumentException("El parámetro 'nexusHost' es obligatorio.")
        }
        if (!config.deployTarget) {
            throw new IllegalArgumentException("El parámetro 'deployTarget' es obligatorio.")
        }

        this.serviceName = config.serviceName
        this.buildType = config.buildType ?: this.buildType
        this.jdkVersion = config.jdkVersion ?: this.jdkVersion
        this.publishJar = config.publishJar != null ? config.publishJar : this.publishJar
        this.publishDocker = config.publishDocker != null ? config.publishDocker : this.publishDocker
        this.nexusHost = config.nexusHost
        this.dockerPort = config.dockerPort ?: this.dockerPort
        this.deployTarget = config.deployTarget
        this.healthEndpoint = config.healthEndpoint ?: this.healthEndpoint
    }
}
