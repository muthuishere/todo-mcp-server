package io.shaama.todoapp.infra;

public interface CloudDeployer {
    void init(String configFilename);
    void setup();
    void deploy();
    void destroy();
    default void showLogs(){

    }
}