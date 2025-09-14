package io.shaama.todoapp.infra;

import io.shaama.todoapp.infra.aws.lambda.AwsLambdaDeployer;
import io.shaama.todoapp.infra.aws.fargate.AwsFargateDeployer;
import io.shaama.todoapp.infra.azure.containerapps.AzureContainerAppsDeployer;
import io.shaama.todoapp.infra.gcp.cloudrun.GcpCloudRunDeployer;

import java.nio.file.Files;
import java.nio.file.Paths;

public class InfraCli {
    public static void main(String[] args) {
        if (args.length < 3) {
            System.err.println("Usage: java InfraCli <provider> <action> <config-file>");
            System.err.println("Providers: aws-lambda, aws-fargate, azure-container-apps, gcp-cloudrun");
            System.err.println("Actions: setup, deploy, destroy, logs");
            System.err.println("Config file: YAML file with serviceName, port, dockerfilePath, environmentFile");
            System.exit(1);
        }

        String provider = args[0];
        String action = args[1];
        String configFile = args[2];


        if (!Files.exists(Paths.get(configFile))) {
            throw new RuntimeException("Config file not found: " + configFile);
        }



        CloudDeployer deployer = switch (provider) {
            case "aws-lambda" -> new AwsLambdaDeployer();
            case "aws-fargate" -> new AwsFargateDeployer();
            case "azure-container-apps" -> new AzureContainerAppsDeployer();
            case "gcp-cloudrun" -> new GcpCloudRunDeployer();
            default -> throw new IllegalArgumentException("Unknown provider: " + provider);
        };



        // Initialize with config file
        deployer.init(configFile);

        // Execute the action
        switch (action) {
            case "setup" -> deployer.setup();
            case "deploy" -> deployer.deploy();
            case "destroy" -> deployer.destroy();
            case "logs" -> deployer.showLogs();
            default -> throw new IllegalArgumentException("Unknown action: " + action);
        }
    }
}