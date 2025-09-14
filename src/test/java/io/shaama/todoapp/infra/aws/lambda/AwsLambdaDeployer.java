package io.shaama.todoapp.infra.aws.lambda;

import io.shaama.todoapp.infra.CloudDeployer;
import io.shaama.todoapp.infra.Runner;
import java.util.Set;
import java.io.FileInputStream;
import java.nio.file.Paths;
import org.yaml.snakeyaml.Yaml;
import java.util.Map;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.ecr.EcrClient;
import software.amazon.awssdk.services.ecr.model.*;
import software.amazon.awssdk.services.iam.IamClient;
import software.amazon.awssdk.services.iam.model.*;
import software.amazon.awssdk.services.lambda.LambdaClient;
import software.amazon.awssdk.services.lambda.model.*;
import software.amazon.awssdk.services.sts.StsClient;
import software.amazon.awssdk.services.sts.model.GetCallerIdentityRequest;
import software.amazon.awssdk.services.cloudwatchlogs.CloudWatchLogsClient;
import software.amazon.awssdk.services.cloudwatchlogs.model.*;
import software.amazon.awssdk.services.lambda.model.ResourceNotFoundException;

public class AwsLambdaDeployer implements CloudDeployer {
    private LambdaConfig config;
    private EcrClient ecrClient;
    private IamClient iamClient;
    private LambdaClient lambdaClient;
    private StsClient stsClient;
    private CloudWatchLogsClient cloudWatchLogsClient;

    private void initializeAwsClients() {
        Region region = Region.of(config.getRegion());
        this.ecrClient = EcrClient.builder().region(region).build();
        this.iamClient = IamClient.builder().region(region).build();
        this.lambdaClient = LambdaClient.builder().region(region).build();
        this.stsClient = StsClient.builder().region(region).build();
        this.cloudWatchLogsClient = CloudWatchLogsClient.builder().region(region).build();
    }

    @Override
    public void init(String configFilename) {
        System.out.println("AWS Lambda: Initializing with config file: " + configFilename);

        try {
            System.out.println("Config file found: " + configFilename);
            
            Yaml yaml = new Yaml();
            try (FileInputStream inputStream = new FileInputStream(configFilename)) {
                this.config = yaml.loadAs(inputStream, LambdaConfig.class);
                
                // Convert relative paths to absolute paths based on config file location
                String configDir = Paths.get(configFilename).getParent().toString();
                
                // Resolve dockerfilePath relative to config directory
                if (!Paths.get(config.getDockerfilePath()).isAbsolute()) {
                    String absoluteDockerPath = Paths.get(configDir, config.getDockerfilePath()).normalize().toString();
                    String patchedDockerfilePath = DockerfilePatcher.createPatchedDockerfile(absoluteDockerPath);

                    System.out.println("Patched dockerfile: " + patchedDockerfilePath);
                    config.setDockerfilePath(patchedDockerfilePath);
                }
                
                // Resolve environmentFile relative to config directory
                if (!Paths.get(config.getEnvironmentFile()).isAbsolute()) {
                    String absoluteEnvPath = Paths.get(configDir, config.getEnvironmentFile()).normalize().toString();
                    config.setEnvironmentFile(absoluteEnvPath);
                }
                
                System.out.println("Loaded config: " + config);
                
                // Initialize AWS clients after config is loaded
                initializeAwsClients();
            }

        } catch (Exception e) {
            System.err.println("Error loading config file: " + e.getMessage());
            throw new RuntimeException("Failed to load config", e);
        }
    }

    @Override
    public void setup() {
        System.out.println("AWS Lambda: Setting up infrastructure for " + config.getFunctionName());
        
        try {
            // Create ECR repository
            System.out.println("- Creating ECR repository: " + config.getServiceName());
            createEcrRepository();
            
            // Create IAM role for Lambda
            System.out.println("- Creating IAM role for Lambda");
            createLambdaRole();
            
            System.out.println("- Infrastructure setup completed");
            
        } catch (Exception e) {
            System.err.println("Setup failed: " + e.getMessage());
            // Continue - resources might already exist
        }
    }
    
    private void createEcrRepository() {
        try {
            // First check if repository exists
            DescribeRepositoriesRequest describeRequest = DescribeRepositoriesRequest.builder()
                .repositoryNames(config.getServiceName())
                .build();
            
            DescribeRepositoriesResponse describeResponse = ecrClient.describeRepositories(describeRequest);
            if (!describeResponse.repositories().isEmpty()) {
                String repositoryUri = describeResponse.repositories().get(0).repositoryUri();
                System.out.println("ECR repository already exists: " + repositoryUri);
                return;
            }
            
        } catch (RepositoryNotFoundException e) {
            // Repository doesn't exist, proceed to create it
        }
        
        try {
            CreateRepositoryRequest request = CreateRepositoryRequest.builder()
                .repositoryName(config.getServiceName())
                .imageTagMutability(ImageTagMutability.MUTABLE)
                .build();
            
            CreateRepositoryResponse response = ecrClient.createRepository(request);
            System.out.println("Created ECR repository: " + response.repository().repositoryUri());
            
        } catch (Exception e) {
            System.err.println("Failed to create ECR repository: " + e.getMessage());
            throw e;
        }
    }
    
    private void createLambdaRole() {
        String roleName = config.getFunctionName() + "-role";
        
        try {
            // First check if role exists
            GetRoleRequest getRoleRequest = GetRoleRequest.builder()
                .roleName(roleName)
                .build();
            
            GetRoleResponse getRoleResponse = iamClient.getRole(getRoleRequest);
            System.out.println("IAM role already exists: " + getRoleResponse.role().arn());
            
            // Check if the required policy is attached
            checkAndAttachPolicy(roleName);
            return;
            
        } catch (NoSuchEntityException e) {
            // Role doesn't exist, proceed to create it
        }
        
        try {
            String trustPolicy = """
                {
                    "Version": "2012-10-17",
                    "Statement": [
                        {
                            "Effect": "Allow",
                            "Principal": {
                                "Service": "lambda.amazonaws.com"
                            },
                            "Action": "sts:AssumeRole"
                        }
                    ]
                }
                """;
            
            CreateRoleRequest createRoleRequest = CreateRoleRequest.builder()
                .roleName(roleName)
                .assumeRolePolicyDocument(trustPolicy)
                .description("IAM role for Lambda function " + config.getFunctionName())
                .build();
            
            CreateRoleResponse response = iamClient.createRole(createRoleRequest);
            System.out.println("Created IAM role: " + response.role().arn());
            
            // Attach the required policy
            attachLambdaExecutionPolicy(roleName);
            
        } catch (Exception e) {
            System.err.println("Failed to create IAM role: " + e.getMessage());
            throw e;
        }
    }
    
    private void checkAndAttachPolicy(String roleName) {
        String policyArn = "arn:aws:iam::aws:policy/service-role/AWSLambdaBasicExecutionRole";
        
        try {
            // Check if policy is already attached
            ListAttachedRolePoliciesRequest listRequest = ListAttachedRolePoliciesRequest.builder()
                .roleName(roleName)
                .build();
            
            ListAttachedRolePoliciesResponse listResponse = iamClient.listAttachedRolePolicies(listRequest);
            
            boolean policyAttached = listResponse.attachedPolicies().stream()
                .anyMatch(policy -> policy.policyArn().equals(policyArn));
            
            if (policyAttached) {
                System.out.println("Lambda execution policy already attached to role");
            } else {
                attachLambdaExecutionPolicy(roleName);
            }
            
        } catch (Exception e) {
            System.err.println("Failed to check policy attachment: " + e.getMessage());
            // Try to attach anyway
            attachLambdaExecutionPolicy(roleName);
        }
    }
    
    private void attachLambdaExecutionPolicy(String roleName) {
        try {
            AttachRolePolicyRequest attachPolicyRequest = AttachRolePolicyRequest.builder()
                .roleName(roleName)
                .policyArn("arn:aws:iam::aws:policy/service-role/AWSLambdaBasicExecutionRole")
                .build();
            
            iamClient.attachRolePolicy(attachPolicyRequest);
            System.out.println("Attached Lambda execution policy to role");
            
        } catch (Exception e) {
            System.err.println("Failed to attach Lambda execution policy: " + e.getMessage());
        }
    }

    @Override
    public void deploy() {
        System.out.println("AWS Lambda: Deploying " + config.getFunctionName());
        
        try {
            // Get AWS account ID and construct ECR repository URL
            String accountId = getAwsAccountId();
            String ecrRepositoryUrl = accountId + ".dkr.ecr." + config.getRegion() + ".amazonaws.com/" + config.getServiceName();
            
            System.out.println("- Building Docker image from Dockerfile.lambda");
            String imageTag = ecrRepositoryUrl + ":latest";
            Runner.runDocker("build", "--platform", "linux/amd64", "--provenance=false", "-t", imageTag, "-f", "Dockerfile.lambda", ".");
            
            // Login to ECR using AWS CLI
            System.out.println("- Logging into ECR");
            String ecrLoginCommand = String.format(
                "aws ecr get-login-password --region %s | docker login --username AWS --password-stdin %s.dkr.ecr.%s.amazonaws.com",
                config.getRegion(), accountId, config.getRegion()
            );
            Runner.runCommand(ecrLoginCommand);
            
            // Push to ECR
            System.out.println("- Pushing image to ECR: " + ecrRepositoryUrl);
            Runner.runDocker("push", imageTag);
            
            // Create or update Lambda function
            System.out.println("- Creating/updating Lambda function");
            createOrUpdateLambdaFunction(imageTag, accountId);
            
            // Create or update Function URL
            System.out.println("- Creating/updating Function URL");
            String functionUrl = createOrUpdateFunctionUrl();
            
            // Add resource-based policy for public access
            System.out.println("- Adding resource-based policy for public Function URL access");
            addFunctionUrlPolicy();
            
            System.out.println("\n=== AWS Lambda Deployment Complete ===");
            System.out.println("Function Name: " + config.getFunctionName());
            System.out.println("Function URL: " + functionUrl);
            System.out.println("Region: " + config.getRegion());
            System.out.println("\nMCP Configuration:");
            System.out.println("Add this to your MCP client config:");

            System.out.println("curl -v "+functionUrl+"/api/health");
            System.out.println("\"" + config.getFunctionName() + "\": {");
            System.out.println("  \"type\": \"http\",");
            System.out.println("  \"url\": \"" + functionUrl + "mcp\"");
            System.out.println("}");
            System.out.println("\nNote: Lambda Function URL is PUBLIC (no authentication required)");
            System.out.println("Using AWS Lambda Web Adapter for Spring Boot web endpoints");
            
        } catch (Exception e) {
            System.err.println("Deployment failed: " + e.getMessage());
            throw new RuntimeException("Deployment failed", e);
        }
    }


// chatgpt 5
    private void createOrUpdateLambdaFunction(String imageUri, String accountId) {
        String roleArn = "arn:aws:iam::" + accountId + ":role/" + config.getFunctionName() + "-role";

        try {
            // Check if function already exists
            try {
                lambdaClient.getFunction(GetFunctionRequest.builder()
                        .functionName(config.getFunctionName())
                        .build());

                System.out.println("- Function exists, updating code and configuration");

                // Update container image
                lambdaClient.updateFunctionCode(UpdateFunctionCodeRequest.builder()
                        .functionName(config.getFunctionName())
                        .imageUri(imageUri)
                        .build());

                // Update configuration
                UpdateFunctionConfigurationResponse configResponse =
                        lambdaClient.updateFunctionConfiguration(UpdateFunctionConfigurationRequest.builder()
                                .functionName(config.getFunctionName())
                                .memorySize(config.getMemorySize())
                                .timeout(config.getTimeout())
                                .environment(Environment.builder()
                                        .variables(Map.of(
                                                "AWS_LWA_INVOKE_MODE", "response_stream",
                                                "AWS_LWA_PORT", "8080"
                                                // Optional override if not set in Dockerfile:
                                                // "AWS_LWA_APP_INIT_COMMAND", "java -jar /var/task/app.jar"
                                        ))
                                        .build())
                                .build());

                System.out.println("Updated Lambda function: " + configResponse.functionArn());

            } catch (ResourceNotFoundException e) {
                // Function doesn't exist, create new
                System.out.println("- Function doesn't exist, creating new one");

                CreateFunctionResponse response = lambdaClient.createFunction(CreateFunctionRequest.builder()
                        .functionName(config.getFunctionName())
                        .role(roleArn)
                        .code(FunctionCode.builder().imageUri(imageUri).build())
                        .packageType(PackageType.IMAGE)
                        .memorySize(config.getMemorySize())
                        .timeout(config.getTimeout())
                        .description("Spring Boot MVC Lambda with Web Adapter")
                        .environment(Environment.builder()
                                .variables(Map.of(
                                        "AWS_LWA_INVOKE_MODE", "response_stream",
                                        "AWS_LWA_PORT", "8080"
                                ))
                                .build())
                        .build());

                System.out.println("Created Lambda function: " + response.functionArn());
            }

        } catch (Exception e) {
            System.err.println("Failed to create/update Lambda function: " + e.getMessage());
            throw e;
        }
    }


    private String createOrUpdateFunctionUrl() {
        try {
            // Check if Function URL already exists
            GetFunctionUrlConfigRequest getRequest = GetFunctionUrlConfigRequest.builder()
                .functionName(config.getFunctionName())
                .build();
            
            try {
                GetFunctionUrlConfigResponse getResponse = lambdaClient.getFunctionUrlConfig(getRequest);
                System.out.println("Function URL already exists: " + getResponse.functionUrl());
                return getResponse.functionUrl();
            } catch (ResourceNotFoundException e) {
                // Function URL doesn't exist, create it
                System.out.println("- Creating new Function URL");
                
//                CreateFunctionUrlConfigRequest createRequest = CreateFunctionUrlConfigRequest.builder()
//                    .functionName(config.getFunctionName())
//                    .authType(FunctionUrlAuthType.NONE) // Public access
//                    .cors(Cors.builder()
//                        .allowCredentials(false)
//                        .allowHeaders("*")
//                        .allowMethods("*")
//                        .allowOrigins("*")
//                        .maxAge(86400)
//                        .build())
//                    .build();
                CreateFunctionUrlConfigRequest createRequest = CreateFunctionUrlConfigRequest.builder()
                        .functionName(config.getFunctionName())
                        .authType(FunctionUrlAuthType.NONE)
                        .invokeMode(InvokeMode.RESPONSE_STREAM)  // Ensure this is set
                        .cors(Cors.builder()
                                .allowCredentials(false)
                                .allowHeaders(Set.of("*"))
                                .allowMethods(Set.of("*"))
                                .allowOrigins(Set.of("*"))
                                .maxAge(86400)
                                .build())
                        .build();

                CreateFunctionUrlConfigResponse createResponse = lambdaClient.createFunctionUrlConfig(createRequest);
                System.out.println("Created Function URL: " + createResponse.functionUrl());
                return createResponse.functionUrl();
            }
            
        } catch (Exception e) {
            System.err.println("Failed to create/update Function URL: " + e.getMessage());
            throw e;
        }
    }
    
    private void addFunctionUrlPolicy() {
        try {
            // Check if policy already exists
            software.amazon.awssdk.services.lambda.model.GetPolicyRequest getRequest = 
                software.amazon.awssdk.services.lambda.model.GetPolicyRequest.builder()
                    .functionName(config.getFunctionName())
                    .build();
            
            try {
                software.amazon.awssdk.services.lambda.model.GetPolicyResponse getResponse = 
                    lambdaClient.getPolicy(getRequest);
                if (getResponse.policy().contains("lambda:InvokeFunctionUrl")) {
                    System.out.println("Function URL policy already exists");
                    return;
                }
            } catch (ResourceNotFoundException e) {
                // No policy exists, we'll create one
            }
            
            // Create resource-based policy for Function URL public access
            AddPermissionRequest addPermissionRequest = AddPermissionRequest.builder()
                .functionName(config.getFunctionName())
                .statementId("FunctionURLAllowPublicAccess")
                .action("lambda:InvokeFunctionUrl")
                .principal("*")
                .functionUrlAuthType(FunctionUrlAuthType.NONE)
                .build();
            
            lambdaClient.addPermission(addPermissionRequest);
            System.out.println("Added Function URL public access policy");
            
        } catch (Exception e) {
            System.err.println("Failed to add Function URL policy: " + e.getMessage());
            // Don't throw - this might already exist or have conflicts
        }
    }
    
    private String getAwsAccountId() {
        try {
            GetCallerIdentityRequest request = GetCallerIdentityRequest.builder().build();
            return stsClient.getCallerIdentity(request).account();
        } catch (Exception e) {
            System.err.println("Failed to get AWS account ID: " + e.getMessage());
            throw e;
        }
    }

    @Override
    public void destroy() {
        System.out.println("AWS Lambda: Destroying ALL infrastructure for " + config.getFunctionName());
        System.out.println("⚠️  This will delete ALL AWS resources to avoid any billing charges!");
        
        try {
            // Delete Lambda function URL first
            System.out.println("- Removing Function URL");
            deleteFunctionUrl();
            
            // Remove Function URL permissions
            System.out.println("- Removing Function URL permissions");
            removeFunctionUrlPolicy();
            
            // Delete CloudWatch Log Groups and Streams (FORCE DELETE - no sharing)
            System.out.println("- Cleaning up CloudWatch logs");
            deleteCloudWatchLogs();
            
            // Delete Lambda function
            System.out.println("- Removing Lambda function");
            deleteLambdaFunction();
            
            // Delete IAM role (FORCE DELETE - no sharing)
            System.out.println("- Removing IAM role");
            deleteLambdaRole();
            
            // Delete ECR repository (FORCE DELETE - no sharing)
            System.out.println("- Cleaning up ECR repository");
            deleteEcrRepository();
            
            // Clean up any Lambda layers we might have created
            System.out.println("- Cleaning up Lambda layers");
            deleteLambdaLayers();

            System.out.println("- Cleaning up event source mappings");
            deleteEventSourceMappings();
            
            System.out.println("✅ AWS Lambda infrastructure COMPLETELY cleaned up - no billing charges!");
            System.out.println("💰 All AWS resources deleted to prevent any unexpected costs!");
            
        } catch (Exception e) {
            System.err.println("Cleanup failed: " + e.getMessage());
            System.err.println("⚠️  CRITICAL: Please check AWS Console manually to ensure all resources are deleted!");
            System.err.println("⚠️  Region: " + config.getRegion());
            System.err.println("⚠️  Function: " + config.getFunctionName());
        }
    }
    
    private void deleteLambdaFunction() {
        try {
            DeleteFunctionRequest request = DeleteFunctionRequest.builder()
                .functionName(config.getFunctionName())
                .build();
            
            lambdaClient.deleteFunction(request);
            System.out.println("Deleted Lambda function: " + config.getFunctionName());
            
        } catch (ResourceNotFoundException e) {
            System.out.println("Lambda function not found or already deleted");
        } catch (Exception e) {
            System.err.println("Failed to delete Lambda function: " + e.getMessage());
        }
    }
    
    private void deleteFunctionUrl() {
        try {
            DeleteFunctionUrlConfigRequest request = DeleteFunctionUrlConfigRequest.builder()
                .functionName(config.getFunctionName())
                .build();
            
            lambdaClient.deleteFunctionUrlConfig(request);
            System.out.println("Deleted Function URL for: " + config.getFunctionName());
            
        } catch (ResourceNotFoundException e) {
            System.out.println("Function URL not found or already deleted");
        } catch (Exception e) {
            System.err.println("Failed to delete Function URL: " + e.getMessage());
        }
    }
    
    private void removeFunctionUrlPolicy() {
        try {
            RemovePermissionRequest request = RemovePermissionRequest.builder()
                .functionName(config.getFunctionName())
                .statementId("FunctionURLAllowPublicAccess")
                .build();
            
            lambdaClient.removePermission(request);
            System.out.println("Removed Function URL policy for: " + config.getFunctionName());
            
        } catch (ResourceNotFoundException e) {
            System.out.println("Function URL policy not found or already removed");
        } catch (Exception e) {
            System.err.println("Failed to remove Function URL policy: " + e.getMessage());
        }
    }
    
    private void deleteLambdaRole() {
        try {
            String roleName = config.getFunctionName() + "-role";
            
            // Detach policies first
            DetachRolePolicyRequest detachRequest = DetachRolePolicyRequest.builder()
                .roleName(roleName)
                .policyArn("arn:aws:iam::aws:policy/service-role/AWSLambdaBasicExecutionRole")
                .build();
            
            iamClient.detachRolePolicy(detachRequest);
            
            // Delete the role
            DeleteRoleRequest deleteRequest = DeleteRoleRequest.builder()
                .roleName(roleName)
                .build();
            
            iamClient.deleteRole(deleteRequest);
            System.out.println("Deleted IAM role: " + roleName);
            
        } catch (NoSuchEntityException e) {
            System.out.println("IAM role not found or already deleted");
        } catch (Exception e) {
            System.err.println("Failed to delete IAM role: " + e.getMessage());
        }
    }
    
    private void deleteCloudWatchLogs() {
        try {
            String logGroupName = "/aws/lambda/" + config.getFunctionName();
            
            // Delete log group (this will also delete all log streams within it)
            DeleteLogGroupRequest deleteRequest = DeleteLogGroupRequest.builder()
                .logGroupName(logGroupName)
                .build();
            
            cloudWatchLogsClient.deleteLogGroup(deleteRequest);
            System.out.println("Deleted CloudWatch log group: " + logGroupName);
            
        } catch (software.amazon.awssdk.services.cloudwatchlogs.model.ResourceNotFoundException e) {
            System.out.println("CloudWatch log group not found or already deleted");
        } catch (Exception e) {
            System.err.println("Failed to delete CloudWatch log group: " + e.getMessage());
        }
    }
    
    private void deleteEcrRepository() {
        try {
            DeleteRepositoryRequest request = DeleteRepositoryRequest.builder()
                .repositoryName(config.getServiceName())
                .force(true) // Delete even if it contains images
                .build();
            
            ecrClient.deleteRepository(request);
            System.out.println("Deleted ECR repository: " + config.getServiceName());
            
        } catch (RepositoryNotFoundException e) {
            System.out.println("ECR repository not found or already deleted");
        } catch (Exception e) {
            System.err.println("Failed to delete ECR repository: " + e.getMessage());
        }
    }


// ...existing code...

    public void showLogs() {
        String logGroupName = "/aws/lambda/" + config.getFunctionName();
        System.out.println("=== Tailing logs for Lambda function: " + config.getFunctionName() + " ===");
        System.out.println("Log Group: " + logGroupName);
        System.out.println("Press Ctrl+C to stop...\n");

        long startTime = System.currentTimeMillis() - (5 * 60 * 1000); // Start from 5 minutes ago
        String nextToken = null;

        try {
            while (true) {
                try {
                    // Get recent log events
                    FilterLogEventsRequest.Builder requestBuilder = FilterLogEventsRequest.builder()
                            .logGroupName(logGroupName)
                            .startTime(startTime)
                            .limit(100);

                    if (nextToken != null) {
                        requestBuilder.nextToken(nextToken);
                    }

                    FilterLogEventsResponse response = cloudWatchLogsClient.filterLogEvents(requestBuilder.build());

                    // Print new log events
                    for (FilteredLogEvent event : response.events()) {
                        printFormattedLogEvent(event);
                        startTime = Math.max(startTime, event.timestamp() + 1); // Update start time
                    }

                    nextToken = response.nextToken();

                    // If no more events and no next token, wait before polling again
                    if (response.events().isEmpty()) {
                        Thread.sleep(2000); // Wait 2 seconds before next poll
                        nextToken = null; // Reset token for fresh search
                    }

                } catch (software.amazon.awssdk.services.cloudwatchlogs.model.ResourceNotFoundException e) {
                    System.err.println("❌ Log group not found: " + logGroupName);
                    System.err.println("   Function may not have been invoked yet or doesn't exist.");
                    Thread.sleep(5000); // Wait 5 seconds before retrying

                } catch (Exception e) {
                    System.err.println("❌ Error reading logs: " + e.getMessage());
                    Thread.sleep(5000); // Wait 5 seconds before retrying
                }
            }

        } catch (InterruptedException e) {
            System.out.println("\n🛑 Log tailing stopped by user");
            Thread.currentThread().interrupt();
        }
    }

    private void printFormattedLogEvent(FilteredLogEvent event) {
        String timestamp = formatTimestamp(event.timestamp());
        String message = event.message().trim();

        // Categorize and format different types of log messages
        if (message.startsWith("START RequestId:")) {
            System.out.println("🚀 " + timestamp + " " + formatStartMessage(message));

        } else if (message.startsWith("END RequestId:")) {
            System.out.println("🏁 " + timestamp + " " + formatEndMessage(message));

        } else if (message.startsWith("REPORT RequestId:")) {
            System.out.println("📊 " + timestamp + " " + formatReportMessage(message));

        } else if (message.startsWith("INIT_REPORT")) {
            System.out.println("⚡ " + timestamp + " " + formatInitMessage(message));

        } else if (message.contains("ERROR") || message.contains("Error") || message.contains("Exception")) {
            System.out.println("❌ " + timestamp + " " + formatErrorMessage(message));

        } else if (message.contains("WARN") || message.contains("Warning")) {
            System.out.println("⚠️  " + timestamp + " " + formatWarningMessage(message));

        } else if (message.startsWith("EXTENSION")) {
            System.out.println("🔌 " + timestamp + " " + formatExtensionMessage(message));

        } else if (message.contains("Started") && message.contains("Application")) {
            System.out.println("🌟 " + timestamp + " " + formatSuccessMessage(message));

        } else if (message.contains("Tomcat started on port") || message.contains("server started")) {
            System.out.println("🌐 " + timestamp + " " + formatSuccessMessage(message));

        } else {
            // Regular application logs
            System.out.println("📝 " + timestamp + " " + message);
        }
    }

    private String formatTimestamp(long timestamp) {
        java.time.Instant instant = java.time.Instant.ofEpochMilli(timestamp);
        java.time.LocalDateTime dateTime = java.time.LocalDateTime.ofInstant(instant, java.time.ZoneId.systemDefault());
        return dateTime.format(java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss.SSS"));
    }

    private String formatStartMessage(String message) {
        // Extract RequestId from "START RequestId: abc-123 Version: $LATEST"
        String[] parts = message.split(" ");
        String requestId = parts.length > 2 ? parts[2] : "unknown";
        return "REQUEST STARTED [" + requestId.substring(0, Math.min(8, requestId.length())) + "...]";
    }

    private String formatEndMessage(String message) {
        String[] parts = message.split(" ");
        String requestId = parts.length > 2 ? parts[2] : "unknown";
        return "REQUEST ENDED [" + requestId.substring(0, Math.min(8, requestId.length())) + "...]";
    }

    private String formatReportMessage(String message) {
        // Extract key metrics from REPORT line
        StringBuilder report = new StringBuilder();

        if (message.contains("Duration:")) {
            String duration = extractValue(message, "Duration:", "ms");
            report.append("⏱️  Duration: ").append(duration).append("ms ");
        }

        if (message.contains("Billed Duration:")) {
            String billedDuration = extractValue(message, "Billed Duration:", "ms");
            report.append("💰 Billed: ").append(billedDuration).append("ms ");
        }

        if (message.contains("Memory Size:")) {
            String memorySize = extractValue(message, "Memory Size:", "MB");
            report.append("🧠 Memory: ").append(memorySize).append("MB ");
        }

        if (message.contains("Max Memory Used:")) {
            String memoryUsed = extractValue(message, "Max Memory Used:", "MB");
            report.append("📈 Used: ").append(memoryUsed).append("MB ");
        }

        if (message.contains("Init Duration:")) {
            String initDuration = extractValue(message, "Init Duration:", "ms");
            report.append("🔄 Init: ").append(initDuration).append("ms ");
        }

        return report.toString().trim();
    }

    private String formatInitMessage(String message) {
        if (message.contains("Status: error")) {
            return "❌ INIT FAILED: " + message.substring(message.indexOf("Status:"));
        } else {
            return "✅ INIT SUCCESS: " + message.substring(message.indexOf("Duration:"));
        }
    }

    private String formatErrorMessage(String message) {
        return "🔥 " + message;
    }

    private String formatWarningMessage(String message) {
        return "⚠️  " + message;
    }

    private String formatExtensionMessage(String message) {
        if (message.contains("lambda-adapter")) {
            return "🔌 Lambda Web Adapter: " + message.substring(message.indexOf("State:"));
        }
        return message;
    }

    private String formatSuccessMessage(String message) {
        return "✅ " + message;
    }

    private String extractValue(String message, String key, String unit) {
        try {
            int startIndex = message.indexOf(key);
            if (startIndex == -1) return "?";

            startIndex += key.length();
            int endIndex = message.indexOf(unit, startIndex);
            if (endIndex == -1) endIndex = message.indexOf("\t", startIndex);
            if (endIndex == -1) endIndex = message.indexOf(" ", startIndex + 1);
            if (endIndex == -1) endIndex = message.length();

            return message.substring(startIndex, endIndex).trim();
        } catch (Exception e) {
            return "?";
        }
    }
    
    private void deleteLambdaLayers() {
        try {
            // Clean up any Lambda layers associated with our function name
            var listLayersRequest = software.amazon.awssdk.services.lambda.model.ListLayersRequest.builder()
                .build();
            
            var response = lambdaClient.listLayers(listLayersRequest);
            for (var layer : response.layers()) {
                if (layer.layerName().contains(config.getFunctionName())) {
                    try {
                        var deleteRequest = software.amazon.awssdk.services.lambda.model.DeleteLayerVersionRequest.builder()
                            .layerName(layer.layerName())
                            .versionNumber(layer.latestMatchingVersion().version())
                            .build();
                        lambdaClient.deleteLayerVersion(deleteRequest);
                        System.out.println("✅ Deleted Lambda layer: " + layer.layerName());
                    } catch (Exception e) {
                        System.out.println("Could not delete layer " + layer.layerName() + ": " + e.getMessage());
                    }
                }
            }
        } catch (Exception e) {
            System.out.println("Could not clean up Lambda layers: " + e.getMessage());
        }
    }
    
    private void deleteEventSourceMappings() {
        try {
            // Clean up any event source mappings for our function
            var listRequest = software.amazon.awssdk.services.lambda.model.ListEventSourceMappingsRequest.builder()
                .functionName(config.getFunctionName())
                .build();
            
            var response = lambdaClient.listEventSourceMappings(listRequest);
            for (var mapping : response.eventSourceMappings()) {
                try {
                    var deleteRequest = software.amazon.awssdk.services.lambda.model.DeleteEventSourceMappingRequest.builder()
                        .uuid(mapping.uuid())
                        .build();
                    lambdaClient.deleteEventSourceMapping(deleteRequest);
                    System.out.println("✅ Deleted event source mapping: " + mapping.uuid());
                } catch (Exception e) {
                    System.out.println("Could not delete event source mapping " + mapping.uuid() + ": " + e.getMessage());
                }
            }
        } catch (Exception e) {
            System.out.println("Could not clean up event source mappings: " + e.getMessage());
        }
    }

// ...existing code...
}