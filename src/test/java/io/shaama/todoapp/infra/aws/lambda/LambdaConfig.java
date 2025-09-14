package io.shaama.todoapp.infra.aws.lambda;

public class LambdaConfig {
    private String serviceName;
    private int port;
    private String dockerfilePath;
    private String environmentFile;
    private String ecrRepository;
    private String region;
    private int memorySize;
    private int timeout;

    // Default constructor
    public LambdaConfig() {}

    // Getters and setters
    public String getFunctionName() {
        return serviceName +"-function";
    }

    public int getPort() {
        return port;
    }

    public void setPort(int port) {
        this.port = port;
    }

    public String getDockerfilePath() {
        return dockerfilePath;
    }

    public void setDockerfilePath(String dockerfilePath) {
        this.dockerfilePath = dockerfilePath;
    }

    public String getEnvironmentFile() {
        return environmentFile;
    }

    public void setEnvironmentFile(String environmentFile) {
        this.environmentFile = environmentFile;
    }

    public String getEcrRepository() {
        return ecrRepository;
    }

    public void setEcrRepository(String ecrRepository) {
        this.ecrRepository = ecrRepository;
    }

    public String getRegion() {
        return region;
    }

    public void setRegion(String region) {
        this.region = region;
    }

    public int getMemorySize() {
        return memorySize;
    }

    public void setMemorySize(int memorySize) {
        this.memorySize = memorySize;
    }

    public int getTimeout() {
        return timeout;
    }

    public void setTimeout(int timeout) {
        this.timeout = timeout;
    }

    @Override
    public String toString() {
        return "LambdaConfig{" +
                "functionName='" + getFunctionName() + '\'' +
                ", dockerfilePath='" + dockerfilePath + '\'' +
                ", environmentFile='" + environmentFile + '\'' +
                ", ecrRepository='" + ecrRepository + '\'' +
                ", region='" + region + '\'' +
                ", memorySize=" + memorySize +
                ", timeout=" + timeout +
                '}';
    }

    public String getServiceName() {
        return serviceName;
    }

    public void setServiceName(String serviceName) {
        this.serviceName = serviceName;
    }
}