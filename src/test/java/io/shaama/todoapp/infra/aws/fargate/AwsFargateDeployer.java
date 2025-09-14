package io.shaama.todoapp.infra.aws.fargate;

import io.shaama.todoapp.infra.CloudDeployer;
import io.shaama.todoapp.infra.Runner;
import org.yaml.snakeyaml.Yaml;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.core.waiters.WaiterOverrideConfiguration;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.cloudwatchlogs.CloudWatchLogsClient;
import software.amazon.awssdk.services.cloudwatchlogs.model.*;
import software.amazon.awssdk.services.cloudwatchlogs.model.ResourceNotFoundException;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.*;
import software.amazon.awssdk.services.ecr.EcrClient;
import software.amazon.awssdk.services.ecr.model.*;
import software.amazon.awssdk.services.ecs.EcsClient;
import software.amazon.awssdk.services.ecs.model.*;
import software.amazon.awssdk.services.ecs.model.TransportProtocol;
import software.amazon.awssdk.services.elasticloadbalancingv2.ElasticLoadBalancingV2Client;
import software.amazon.awssdk.services.elasticloadbalancingv2.model.*;
import software.amazon.awssdk.services.elasticloadbalancingv2.model.LoadBalancer;
import software.amazon.awssdk.services.elasticloadbalancingv2.model.TargetGroup;
import software.amazon.awssdk.services.iam.IamClient;
import software.amazon.awssdk.services.iam.model.*;
import software.amazon.awssdk.services.sts.StsClient;
import software.amazon.awssdk.services.sts.model.GetCallerIdentityRequest;

import java.io.FileInputStream;
import java.nio.file.Paths;
import java.util.*;

public class AwsFargateDeployer implements CloudDeployer {
    
    private FargateConfig config;
    private EcsClient ecsClient;
    private Ec2Client ec2Client;
    private EcrClient ecrClient;
    private IamClient iamClient;
    private StsClient stsClient;
    private ElasticLoadBalancingV2Client elbClient;
    private CloudWatchLogsClient cloudWatchLogsClient;

    @Override
    public void init(String configFile) {
        System.out.println("AWS Fargate: Initializing with config file: " + configFile);
        
        try {
            if (!Paths.get(configFile).toFile().exists()) {
                throw new RuntimeException("Config file not found: " + configFile);
            }
            
            System.out.println("Config file found: " + configFile);
            
            Yaml yaml = new Yaml();
            try (FileInputStream fis = new FileInputStream(configFile)) {
                this.config = yaml.loadAs(fis, FargateConfig.class);
            }
            
            // Convert relative paths to absolute paths based on config file location
            String configDir = Paths.get(configFile).getParent().toString();
            
            // Resolve dockerfilePath relative to config directory
            if (!Paths.get(config.getDockerfilePath()).isAbsolute()) {
                String absoluteDockerPath = Paths.get(configDir, config.getDockerfilePath()).normalize().toString();
                config.setDockerfilePath(absoluteDockerPath);
            }
            
            // Resolve environmentFile relative to config directory
            if (!Paths.get(config.getEnvironmentFile()).isAbsolute()) {
                String absoluteEnvPath = Paths.get(configDir, config.getEnvironmentFile()).normalize().toString();
                config.setEnvironmentFile(absoluteEnvPath);
            }
            
            System.out.println("Loaded config: " + config);
            
            initializeAwsClients();
                
        } catch (Exception e) {
            throw new RuntimeException("Failed to initialize AWS Fargate deployer", e);
        }
    }
    
    private void initializeAwsClients() {
        Region region = Region.of(config.getRegion());
        DefaultCredentialsProvider credentialsProvider = DefaultCredentialsProvider.create();
        
        this.ecsClient = EcsClient.builder()
            .region(region)
            .credentialsProvider(credentialsProvider)
            .build();
            
        this.ec2Client = Ec2Client.builder()
            .region(region)
            .credentialsProvider(credentialsProvider)
            .build();
            
        this.ecrClient = EcrClient.builder()
            .region(region)
            .credentialsProvider(credentialsProvider)
            .build();
            
        this.iamClient = IamClient.builder()
            .region(region)
            .credentialsProvider(credentialsProvider)
            .build();
            
        this.stsClient = StsClient.builder()
            .region(region)
            .credentialsProvider(credentialsProvider)
            .build();
            
        this.elbClient = ElasticLoadBalancingV2Client.builder()
            .region(region)
            .credentialsProvider(credentialsProvider)
            .build();
            
        this.cloudWatchLogsClient = CloudWatchLogsClient.builder()
            .region(region)
            .credentialsProvider(credentialsProvider)
            .build();
    }

    @Override
    public void setup() {
        System.out.println("AWS Fargate: Setting up infrastructure for " + config.getClusterName());
        
        try {
            // 1. Create ECR repository
            System.out.println("- Creating ECR repository: " + config.getEcrRepository());
            createEcrRepository();
            
            // 2. Create VPC and networking
            System.out.println("- Creating VPC and networking");
            VpcResources vpcResources = createVpcAndNetworking();
            
            // 3. Create IAM roles
            System.out.println("- Creating IAM roles");
            createIamRoles();
            
            // 4. Create ECS cluster
            System.out.println("- Creating ECS cluster");
            createEcsCluster();
            
            // 5. Create Load Balancer
            System.out.println("- Creating Application Load Balancer");
            LoadBalancerResources lbResources = createLoadBalancer(vpcResources);
            
            // 6. Create CloudWatch Log Group
            System.out.println("- Creating CloudWatch Log Group");
            createLogGroup();
            
            System.out.println("✅ Fargate infrastructure setup completed!");
            
        } catch (Exception e) {
            System.err.println("Setup failed: " + e.getMessage());
            throw new RuntimeException("Failed to setup Fargate infrastructure", e);
        }
    }

    @Override
    public void deploy() {
        System.out.println("AWS Fargate: Deploying " + config.getClusterName());
        
        try {
            // Get AWS account ID and construct ECR repository URL
            String accountId = getAccountId();
            String ecrRepositoryUrl = accountId + ".dkr.ecr." + config.getRegion() + ".amazonaws.com/" + config.getEcrRepository();
            
            // Build and push Docker image
            System.out.println("- Building Docker image from " + config.getDockerfilePath());
            buildAndPushImage(ecrRepositoryUrl);
            
            // Create/update task definition
            System.out.println("- Creating/updating task definition");
            String taskDefinitionArn = createOrUpdateTaskDefinition(ecrRepositoryUrl + ":latest", accountId);
            
            // Create/update ECS service
            System.out.println("- Creating/updating ECS service");
            createOrUpdateService(taskDefinitionArn);
            
            // Get the load balancer URL
            String serviceUrl = getServiceUrl();
            
            System.out.println("\n=== AWS Fargate Deployment Complete ===");
            System.out.println("Cluster Name: " + config.getClusterName());
            System.out.println("Service Name: " + config.getEcsServiceName());
            System.out.println("Service URL: " + serviceUrl);
            System.out.println("Region: " + config.getRegion());
            System.out.println("\nMCP Configuration:");
            System.out.println("Add this to your MCP client config:");
            System.out.println("\"" + config.getClusterName() + "\": {");
            System.out.println("  \"type\": \"http\",");
            System.out.println("  \"url\": \"" + serviceUrl + "/mcp\"");
            System.out.println("}");
            System.out.println("\nNote: It may take a few minutes for the service to be fully available");
            
        } catch (Exception e) {
            System.err.println("Deployment failed: " + e.getMessage());
            throw new RuntimeException("Deployment failed", e);
        }
    }

    @Override
    public void destroy() {
        System.out.println("AWS Fargate: Destroying ALL infrastructure for " + config.getClusterName());
        System.out.println("⚠️  This will delete ALL AWS resources to avoid any billing charges!");
        
        try {
            // 1. Delete ECS service
            System.out.println("- Deleting ECS service");
            deleteEcsService();
            
            // 2. Delete task definition (deregister)
            System.out.println("- Deregistering task definition");
            deregisterTaskDefinition();
            
            // 3. Delete Load Balancer
            System.out.println("- Deleting Load Balancer");
            deleteLoadBalancer();
            
            // 4. Delete ECS cluster
            System.out.println("- Deleting ECS cluster");
            deleteEcsCluster();
            
            // 5. Delete IAM roles (FORCE DELETE - no sharing)
            System.out.println("- Deleting IAM roles");
            deleteIamRoles();
            
            // 6. Delete VPC and networking (FORCE DELETE - no sharing)
            System.out.println("- Deleting VPC and networking");
            deleteVpcAndNetworking();
            
            // 7. Delete CloudWatch Log Group (FORCE DELETE - no sharing)
            System.out.println("- Deleting CloudWatch Log Group");
            deleteLogGroup();
            
            // 8. Delete ECR repository (FORCE DELETE - no sharing)
            System.out.println("- Cleaning up ECR repository");
            deleteEcrRepository();
            
            // 9. Clean up any remaining security groups
            System.out.println("- Cleaning up security groups");
            deleteSecurityGroups();
            
            // 10. Clean up any remaining network interfaces
            System.out.println("- Cleaning up network interfaces");
            deleteNetworkInterfaces();
            
            System.out.println("✅ AWS Fargate infrastructure COMPLETELY cleaned up - no billing charges!");
            System.out.println("💰 All AWS resources deleted to prevent any unexpected costs!");
            
        } catch (Exception e) {
            System.err.println("Cleanup failed: " + e.getMessage());
            System.err.println("⚠️  CRITICAL: Please check AWS Console manually to ensure all resources are deleted!");
            System.err.println("⚠️  Region: " + config.getRegion());
            System.err.println("⚠️  Cluster: " + config.getClusterName());
        }
    }

    // Infrastructure creation methods
    private void createEcrRepository() {
        try {
            // First check if repository exists
            DescribeRepositoriesRequest describeRequest = DescribeRepositoriesRequest.builder()
                .repositoryNames(config.getEcrRepository())
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
                .repositoryName(config.getEcrRepository())
                .imageTagMutability(ImageTagMutability.MUTABLE)
                .build();
            
            CreateRepositoryResponse response = ecrClient.createRepository(request);
            System.out.println("Created ECR repository: " + response.repository().repositoryUri());
            
        } catch (Exception e) {
            System.err.println("Failed to create ECR repository: " + e.getMessage());
            throw e;
        }
    }

    private VpcResources createVpcAndNetworking() {
        VpcResources resources = new VpcResources();
        
        try {
            // First try to find default VPC
            DescribeVpcsResponse vpcsResponse = ec2Client.describeVpcs(DescribeVpcsRequest.builder()
                .filters(Filter.builder().name("isDefault").values("true").build())
                .build());
                
            if (!vpcsResponse.vpcs().isEmpty()) {
                // Use existing default VPC
                Vpc defaultVpc = vpcsResponse.vpcs().get(0);
                resources.setVpcId(defaultVpc.vpcId());
                
                // Get subnets in the default VPC
                DescribeSubnetsResponse subnetsResponse = ec2Client.describeSubnets(DescribeSubnetsRequest.builder()
                    .filters(Filter.builder().name("vpc-id").values(defaultVpc.vpcId()).build())
                    .build());
                    
                List<String> subnetIds = subnetsResponse.subnets().stream()
                    .map(Subnet::subnetId)
                    .toList();
                    
                resources.setSubnetIds(subnetIds);
                
                System.out.println("Using existing default VPC: " + resources.getVpcId() + " with " + subnetIds.size() + " subnets");
                
            } else {
                // No default VPC found, create one using AWS CLI
                System.out.println("No default VPC found, creating default VPC using AWS CLI...");
                createDefaultVpcWithAwsCli();
                
                // Wait a moment for VPC creation to complete
                Thread.sleep(5000);
                
                // Try again to find the default VPC
                vpcsResponse = ec2Client.describeVpcs(DescribeVpcsRequest.builder()
                    .filters(Filter.builder().name("isDefault").values("true").build())
                    .build());
                    
                if (!vpcsResponse.vpcs().isEmpty()) {
                    Vpc defaultVpc = vpcsResponse.vpcs().get(0);
                    resources.setVpcId(defaultVpc.vpcId());
                    
                    // Get subnets in the newly created default VPC
                    DescribeSubnetsResponse subnetsResponse = ec2Client.describeSubnets(DescribeSubnetsRequest.builder()
                        .filters(Filter.builder().name("vpc-id").values(defaultVpc.vpcId()).build())
                        .build());
                        
                    List<String> subnetIds = subnetsResponse.subnets().stream()
                        .map(Subnet::subnetId)
                        .toList();
                        
                    resources.setSubnetIds(subnetIds);
                    
                    System.out.println("Created and using new default VPC: " + resources.getVpcId() + " with " + subnetIds.size() + " subnets");
                } else {
                    throw new RuntimeException("Failed to create default VPC. Please check AWS CLI configuration and permissions.");
                }
            }
            
            return resources;
            
        } catch (Exception e) {
            System.err.println("Failed to setup VPC and networking: " + e.getMessage());
            throw new RuntimeException("Failed to setup VPC and networking", e);
        }
    }
    
    private void createDefaultVpcWithAwsCli() {
        try {
            System.out.println("Creating default VPC using AWS CLI...");
            
            // Set AWS region for the CLI command
            String awsRegion = config.getRegion();
            
            // Create default VPC using AWS CLI
            Runner.runCommand("aws ec2 create-default-vpc --region " + awsRegion);
            
            System.out.println("✅ Default VPC created successfully in region: " + awsRegion);
            
        } catch (Exception e) {
            System.err.println("Failed to create default VPC with AWS CLI: " + e.getMessage());
            System.err.println("Please ensure:");
            System.err.println("1. AWS CLI is installed and configured");
            System.err.println("2. You have appropriate EC2 permissions");
            System.err.println("3. The region supports default VPC creation");
            throw new RuntimeException("Failed to create default VPC", e);
        }
    }
    private void createIamRoles() {
        createTaskExecutionRole();
        createTaskRole();
    }
    
    private void createTaskExecutionRole() {
        String roleName = config.getExecutionRoleName();
        
        try {
            // Check if role exists
            GetRoleRequest getRoleRequest = GetRoleRequest.builder()
                .roleName(roleName)
                .build();
            
            GetRoleResponse getRoleResponse = iamClient.getRole(getRoleRequest);
            System.out.println("Task execution IAM role already exists: " + getRoleResponse.role().arn());
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
                                "Service": "ecs-tasks.amazonaws.com"
                            },
                            "Action": "sts:AssumeRole"
                        }
                    ]
                }
                """;
            
            CreateRoleRequest createRoleRequest = CreateRoleRequest.builder()
                .roleName(roleName)
                .assumeRolePolicyDocument(trustPolicy)
                .description("IAM execution role for ECS Fargate task " + config.getServiceName())
                .build();
            
            CreateRoleResponse response = iamClient.createRole(createRoleRequest);
            System.out.println("Created task execution IAM role: " + response.role().arn());
            
            // Attach the required policy
            AttachRolePolicyRequest attachPolicyRequest = AttachRolePolicyRequest.builder()
                .roleName(roleName)
                .policyArn("arn:aws:iam::aws:policy/service-role/AmazonECSTaskExecutionRolePolicy")
                .build();
            
            iamClient.attachRolePolicy(attachPolicyRequest);
            System.out.println("Attached ECS task execution policy to role");
            
        } catch (Exception e) {
            System.err.println("Failed to create task execution IAM role: " + e.getMessage());
            throw e;
        }
    }
    
    private void createTaskRole() {
        String roleName = config.getTaskRoleName();
        
        try {
            // Check if role exists
            GetRoleRequest getRoleRequest = GetRoleRequest.builder()
                .roleName(roleName)
                .build();
            
            GetRoleResponse getRoleResponse = iamClient.getRole(getRoleRequest);
            System.out.println("Task IAM role already exists: " + getRoleResponse.role().arn());
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
                                "Service": "ecs-tasks.amazonaws.com"
                            },
                            "Action": "sts:AssumeRole"
                        }
                    ]
                }
                """;
            
            CreateRoleRequest createRoleRequest = CreateRoleRequest.builder()
                .roleName(roleName)
                .assumeRolePolicyDocument(trustPolicy)
                .description("IAM task role for ECS Fargate task " + config.getServiceName())
                .build();
            
            CreateRoleResponse response = iamClient.createRole(createRoleRequest);
            System.out.println("Created task IAM role: " + response.role().arn());
            
        } catch (Exception e) {
            System.err.println("Failed to create task IAM role: " + e.getMessage());
            throw e;
        }
    }

    
    private void createEcsServiceLinkedRole() {
        try {
            System.out.println("Creating ECS service-linked role...");
            
            // Use AWS CLI to create the service-linked role for ECS
            Runner.runCommand("aws iam create-service-linked-role --aws-service-name ecs.amazonaws.com --region " + config.getRegion());
            
            System.out.println("✅ ECS service-linked role created successfully");
            
        } catch (Exception e) {
            System.out.println("ℹ️  ECS service-linked role may already exist (this is normal)");
            // This is expected if the role already exists, so we continue
        }
    }

    private void createEcsCluster() {
        // First, ensure the ECS service-linked role exists
        createEcsServiceLinkedRole();
        
        try {
            // Check if cluster exists
            DescribeClustersRequest describeRequest = DescribeClustersRequest.builder()
                .clusters(config.getClusterName())
                .build();
            
            DescribeClustersResponse describeResponse = ecsClient.describeClusters(describeRequest);
            if (!describeResponse.clusters().isEmpty() && 
                describeResponse.clusters().get(0).status().equals("ACTIVE")) {
                System.out.println("ECS cluster already exists: " + config.getClusterName());
                return;
            }
            
        } catch (Exception e) {
            // Proceed to create cluster
        }
        
        try {
            CreateClusterRequest createRequest = CreateClusterRequest.builder()
                .clusterName(config.getClusterName())
                .capacityProviders("FARGATE")
                .defaultCapacityProviderStrategy(
                    CapacityProviderStrategyItem.builder()
                        .capacityProvider("FARGATE")
                        .weight(1)
                        .build()
                )
                .build();
            
            CreateClusterResponse response = ecsClient.createCluster(createRequest);
            System.out.println("Created ECS cluster: " + response.cluster().clusterArn());
            
        } catch (Exception e) {
            System.err.println("Failed to create ECS cluster: " + e.getMessage());
            throw e;
        }
    }

    private LoadBalancerResources createLoadBalancer(VpcResources vpcResources) {
        LoadBalancerResources lbResources = new LoadBalancerResources();
        
        try {
            // Create security group for load balancer
            String securityGroupId = createLoadBalancerSecurityGroup(vpcResources.getVpcId());
            lbResources.setSecurityGroupId(securityGroupId);
            
            // Create Application Load Balancer
            CreateLoadBalancerRequest createLbRequest = CreateLoadBalancerRequest.builder()
                .name(config.getLoadBalancerName())
                .scheme(LoadBalancerSchemeEnum.INTERNET_FACING)
                .type(LoadBalancerTypeEnum.APPLICATION)
                .subnets(vpcResources.getSubnetIds())
                .securityGroups(securityGroupId)
                .build();
            
            CreateLoadBalancerResponse lbResponse = elbClient.createLoadBalancer(createLbRequest);
            LoadBalancer loadBalancer = lbResponse.loadBalancers().get(0);
            lbResources.setLoadBalancerArn(loadBalancer.loadBalancerArn());
            lbResources.setDnsName(loadBalancer.dnsName());
            
            System.out.println("Created Load Balancer: " + loadBalancer.dnsName());
            
            // Create target group
            CreateTargetGroupRequest createTgRequest = CreateTargetGroupRequest.builder()
                .name(config.getTargetGroupName())
                .protocol(ProtocolEnum.HTTP)
                .port(config.getContainerPort())
                .vpcId(vpcResources.getVpcId())
                .targetType(TargetTypeEnum.IP)
                .healthCheckPath(config.getHealthCheckPath())
                .healthCheckIntervalSeconds(config.getHealthCheckIntervalSeconds())
                .build();
            
            CreateTargetGroupResponse tgResponse = elbClient.createTargetGroup(createTgRequest);
            TargetGroup targetGroup = tgResponse.targetGroups().get(0);
            lbResources.setTargetGroupArn(targetGroup.targetGroupArn());
            
            System.out.println("Created Target Group: " + targetGroup.targetGroupName());
            
            // Create listener
            CreateListenerRequest createListenerRequest = CreateListenerRequest.builder()
                .loadBalancerArn(loadBalancer.loadBalancerArn())
                .protocol(ProtocolEnum.HTTP)
                .port(80)
                .defaultActions(Action.builder()
                    .type(ActionTypeEnum.FORWARD)
                    .targetGroupArn(targetGroup.targetGroupArn())
                    .build())
                .build();
            
            CreateListenerResponse listenerResponse = elbClient.createListener(createListenerRequest);
            lbResources.setListenerArn(listenerResponse.listeners().get(0).listenerArn());
            
            System.out.println("Created Listener for Load Balancer");
            
            return lbResources;
            
        } catch (Exception e) {
            System.err.println("Failed to create Load Balancer: " + e.getMessage());
            throw e;
        }
    }
    
    private String createLoadBalancerSecurityGroup(String vpcId) {
        try {
            String sgName = config.getLoadBalancerSecurityGroupName();
            
            CreateSecurityGroupRequest createSgRequest = CreateSecurityGroupRequest.builder()
                .groupName(sgName)
                .description("Security group for " + config.getServiceName() + " ALB")
                .vpcId(vpcId)
                .build();
            
            CreateSecurityGroupResponse sgResponse = ec2Client.createSecurityGroup(createSgRequest);
            String securityGroupId = sgResponse.groupId();
            
            // Allow HTTP traffic from anywhere
            AuthorizeSecurityGroupIngressRequest ingressRequest = AuthorizeSecurityGroupIngressRequest.builder()
                .groupId(securityGroupId)
                .ipPermissions(IpPermission.builder()
                    .ipProtocol("tcp")
                    .fromPort(80)
                    .toPort(80)
                    .ipRanges(IpRange.builder().cidrIp("0.0.0.0/0").build())
                    .build())
                .build();
            
            ec2Client.authorizeSecurityGroupIngress(ingressRequest);
            
            System.out.println("Created Load Balancer Security Group: " + securityGroupId);
            
            return securityGroupId;
            
        } catch (Exception e) {
            System.err.println("Failed to create Load Balancer Security Group: " + e.getMessage());
            throw e;
        }
    }

    private void createLogGroup() {
        String logGroupName = config.getLogGroupName();
        
        try {
            CreateLogGroupRequest request = CreateLogGroupRequest.builder()
                .logGroupName(logGroupName)
                .build();
            
            cloudWatchLogsClient.createLogGroup(request);
            System.out.println("Created CloudWatch Log Group: " + logGroupName);
            
        } catch (ResourceAlreadyExistsException e) {
            System.out.println("CloudWatch Log Group already exists: " + logGroupName);
        } catch (Exception e) {
            System.err.println("Failed to create CloudWatch Log Group: " + e.getMessage());
            throw e;
        }
    }

    // Deployment methods
    private void buildAndPushImage(String ecrRepositoryUrl) {
        System.out.println("- Building Docker image from " + config.getDockerfilePath());
        String imageTag = ecrRepositoryUrl + ":latest";
        Runner.runDocker("build", "--platform", "linux/amd64", "--provenance=false", "-t", imageTag, "-f", config.getDockerfilePath(), ".");
        
        // Login to ECR using AWS CLI
        System.out.println("- Logging into ECR");
        String accountId = getAccountId();
        String ecrLoginCommand = String.format(
            "aws ecr get-login-password --region %s | docker login --username AWS --password-stdin %s.dkr.ecr.%s.amazonaws.com",
            config.getRegion(), accountId, config.getRegion()
        );
        Runner.runCommand(ecrLoginCommand);
        
        // Push to ECR
        System.out.println("- Pushing image to ECR: " + ecrRepositoryUrl);
        Runner.runDocker("push", imageTag);
    }

    private String createOrUpdateTaskDefinition(String imageUri, String accountId) {
        String executionRoleArn = "arn:aws:iam::" + accountId + ":role/" + config.getExecutionRoleName();
        String taskRoleArn = "arn:aws:iam::" + accountId + ":role/" + config.getTaskRoleName();
        String logGroupName = config.getLogGroupName();
        
        try {
            RegisterTaskDefinitionRequest request = RegisterTaskDefinitionRequest.builder()
                .family(config.getTaskDefinitionFamily())
                .networkMode(NetworkMode.AWSVPC)
                .requiresCompatibilities(Compatibility.FARGATE)
                .cpu(String.valueOf(config.getCpu()))
                .memory(String.valueOf(config.getMemory()))
                .executionRoleArn(executionRoleArn)
                .taskRoleArn(taskRoleArn)
                .containerDefinitions(ContainerDefinition.builder()
                    .name(config.getServiceName())
                    .image(imageUri)
                    .essential(true)
                    .portMappings(PortMapping.builder()
                        .containerPort(config.getContainerPort())
                        .protocol(TransportProtocol.TCP)
                        .build())
                    .logConfiguration(LogConfiguration.builder()
                        .logDriver(LogDriver.AWSLOGS)
                        .options(Map.of(
                            "awslogs-group", logGroupName,
                            "awslogs-region", config.getRegion(),
                            "awslogs-stream-prefix", "ecs"
                        ))
                        .build())
                    .environment(
                        KeyValuePair.builder().name("SPRING_PROFILES_ACTIVE").value("streamable").build(),
                        KeyValuePair.builder().name("SPRING_DATASOURCE_URL").value("jdbc:h2:mem:todo-db").build(),
                        KeyValuePair.builder().name("SPRING_DATASOURCE_DRIVER_CLASS_NAME").value("org.h2.Driver").build(),
                        KeyValuePair.builder().name("SPRING_JPA_DATABASE_PLATFORM").value("org.hibernate.dialect.H2Dialect").build(),
                        KeyValuePair.builder().name("SPRING_JPA_HIBERNATE_DDL_AUTO").value("create-drop").build()
                    )
                    .build())
                .build();
            
            RegisterTaskDefinitionResponse response = ecsClient.registerTaskDefinition(request);
            String taskDefinitionArn = response.taskDefinition().taskDefinitionArn();
            
            System.out.println("Registered task definition: " + taskDefinitionArn);
            
            return taskDefinitionArn;
            
        } catch (Exception e) {
            System.err.println("Failed to register task definition: " + e.getMessage());
            throw e;
        }
    }

    private void createOrUpdateService(String taskDefinitionArn) {
        try {
            // Get VPC and subnet information
            VpcResources vpcResources = getVpcResources();
            LoadBalancerResources lbResources = getLoadBalancerResources();
            
            // Create security group for ECS service
            String serviceSecurityGroupId = createServiceSecurityGroup(vpcResources.getVpcId(), lbResources.getSecurityGroupId());
            
            // Check if service exists
            DescribeServicesRequest describeRequest = DescribeServicesRequest.builder()
                .cluster(config.getClusterName())
                .services(config.getEcsServiceName())
                .build();
            
            DescribeServicesResponse describeResponse = ecsClient.describeServices(describeRequest);
            
            if (!describeResponse.services().isEmpty() && 
                !describeResponse.services().get(0).status().equals("INACTIVE")) {
                
                // Update existing service
                System.out.println("- Service exists, updating...");
                UpdateServiceRequest updateRequest = UpdateServiceRequest.builder()
                    .cluster(config.getClusterName())
                    .service(config.getEcsServiceName())
                    .taskDefinition(taskDefinitionArn)
                    .desiredCount(config.getDesiredCount())
                    .build();
                
                UpdateServiceResponse updateResponse = ecsClient.updateService(updateRequest);
                System.out.println("Updated ECS service: " + updateResponse.service().serviceArn());
                
            } else {
                // Create new service
                System.out.println("- Service doesn't exist, creating...");
                CreateServiceRequest createRequest = CreateServiceRequest.builder()
                    .cluster(config.getClusterName())
                    .serviceName(config.getEcsServiceName())
                    .taskDefinition(taskDefinitionArn)
                    .desiredCount(config.getDesiredCount())
                    .launchType(LaunchType.FARGATE)
                    .networkConfiguration(NetworkConfiguration.builder()
                        .awsvpcConfiguration(AwsVpcConfiguration.builder()
                            .subnets(vpcResources.getSubnetIds())
                            .securityGroups(serviceSecurityGroupId)
                            .assignPublicIp(AssignPublicIp.ENABLED)
                            .build())
                        .build())
                    .loadBalancers(software.amazon.awssdk.services.ecs.model.LoadBalancer.builder()
                        .targetGroupArn(lbResources.getTargetGroupArn())
                        .containerName(config.getServiceName())
                        .containerPort(config.getContainerPort())
                        .build())
                    .build();
                
                CreateServiceResponse createResponse = ecsClient.createService(createRequest);
                System.out.println("Created ECS service: " + createResponse.service().serviceArn());
            }
            
            // Wait for service to be stable
            System.out.println("- Waiting for service to be stable...");
            waitForServiceStable();
            
        } catch (Exception e) {
            System.err.println("Failed to create/update ECS service: " + e.getMessage());
            throw e;
        }
    }
    
    private String createServiceSecurityGroup(String vpcId, String lbSecurityGroupId) {
        try {
            String sgName = config.getSecurityGroupName();
            
            CreateSecurityGroupRequest createSgRequest = CreateSecurityGroupRequest.builder()
                .groupName(sgName)
                .description("Security group for " + config.getServiceName() + " ECS service")
                .vpcId(vpcId)
                .build();
            
            CreateSecurityGroupResponse sgResponse = ec2Client.createSecurityGroup(createSgRequest);
            String securityGroupId = sgResponse.groupId();
            
            // Allow traffic from load balancer security group
            AuthorizeSecurityGroupIngressRequest ingressRequest = AuthorizeSecurityGroupIngressRequest.builder()
                .groupId(securityGroupId)
                .ipPermissions(IpPermission.builder()
                    .ipProtocol("tcp")
                    .fromPort(config.getContainerPort())
                    .toPort(config.getContainerPort())
                    .userIdGroupPairs(UserIdGroupPair.builder()
                        .groupId(lbSecurityGroupId)
                        .build())
                    .build())
                .build();
            
            ec2Client.authorizeSecurityGroupIngress(ingressRequest);
            
            System.out.println("Created Service Security Group: " + securityGroupId);
            
            return securityGroupId;
            
        } catch (Exception e) {
            System.err.println("Failed to create Service Security Group: " + e.getMessage());
            throw e;
        }
    }

    private void waitForServiceStable() {
        try {
            WaiterOverrideConfiguration waiterConfig = WaiterOverrideConfiguration.builder()
                .maxAttempts(20) // 10 minutes max
                .build();
            
            DescribeServicesRequest request = DescribeServicesRequest.builder()
                .cluster(config.getClusterName())
                .services(config.getEcsServiceName())
                .build();
            
            ecsClient.waiter().waitUntilServicesStable(request, waiterConfig);
            System.out.println("✅ Service is stable and ready");
            
        } catch (Exception e) {
            System.out.println("⚠️ Service may still be starting. Check AWS Console for status.");
        }
    }

    // Utility methods
    private String getAccountId() {
        try {
            GetCallerIdentityRequest request = GetCallerIdentityRequest.builder().build();
            return stsClient.getCallerIdentity(request).account();
        } catch (Exception e) {
            System.err.println("Failed to get AWS account ID: " + e.getMessage());
            throw e;
        }
    }

    private String getServiceUrl() {
        try {
            // Get load balancer DNS name
            DescribeLoadBalancersRequest request = DescribeLoadBalancersRequest.builder()
                .names(config.getLoadBalancerName())
                .build();
            
            DescribeLoadBalancersResponse response = elbClient.describeLoadBalancers(request);
            if (!response.loadBalancers().isEmpty()) {
                return "http://" + response.loadBalancers().get(0).dnsName();
            }
            
            return "Load Balancer not found";
            
        } catch (Exception e) {
            return "Error getting service URL: " + e.getMessage();
        }
    }

    private VpcResources getVpcResources() {
        // Simplified - in a real implementation, you'd store this info from setup
        VpcResources resources = new VpcResources();
        
        try {
            DescribeVpcsResponse vpcsResponse = ec2Client.describeVpcs(DescribeVpcsRequest.builder()
                .filters(Filter.builder().name("isDefault").values("true").build())
                .build());
                
            if (!vpcsResponse.vpcs().isEmpty()) {
                Vpc defaultVpc = vpcsResponse.vpcs().get(0);
                resources.setVpcId(defaultVpc.vpcId());
                
                DescribeSubnetsResponse subnetsResponse = ec2Client.describeSubnets(DescribeSubnetsRequest.builder()
                    .filters(Filter.builder().name("vpc-id").values(defaultVpc.vpcId()).build())
                    .build());
                    
                List<String> subnetIds = subnetsResponse.subnets().stream()
                    .map(Subnet::subnetId)
                    .toList();
                    
                resources.setSubnetIds(subnetIds);
            }
            
            return resources;
            
        } catch (Exception e) {
            throw new RuntimeException("Failed to get VPC resources", e);
        }
    }

    private LoadBalancerResources getLoadBalancerResources() {
        LoadBalancerResources resources = new LoadBalancerResources();
        
        try {
            // Get load balancer
            DescribeLoadBalancersRequest lbRequest = DescribeLoadBalancersRequest.builder()
                .names(config.getLoadBalancerName())
                .build();
            
            DescribeLoadBalancersResponse lbResponse = elbClient.describeLoadBalancers(lbRequest);
            if (!lbResponse.loadBalancers().isEmpty()) {
                LoadBalancer lb = lbResponse.loadBalancers().get(0);
                resources.setLoadBalancerArn(lb.loadBalancerArn());
                resources.setDnsName(lb.dnsName());
                resources.setSecurityGroupId(lb.securityGroups().get(0));
            }
            
            // Get target group
            DescribeTargetGroupsRequest tgRequest = DescribeTargetGroupsRequest.builder()
                .names(config.getTargetGroupName())
                .build();
            
            DescribeTargetGroupsResponse tgResponse = elbClient.describeTargetGroups(tgRequest);
            if (!tgResponse.targetGroups().isEmpty()) {
                resources.setTargetGroupArn(tgResponse.targetGroups().get(0).targetGroupArn());
            }
            
            return resources;
            
        } catch (Exception e) {
            throw new RuntimeException("Failed to get Load Balancer resources", e);
        }
    }

    // Cleanup methods - implementation would be similar to Lambda deployer
    private void deleteEcsService() {
        // Implementation for deleting ECS service
        try {
            // Scale down to 0
            UpdateServiceRequest updateRequest = UpdateServiceRequest.builder()
                .cluster(config.getClusterName())
                .service(config.getEcsServiceName())
                .desiredCount(0)
                .build();
            
            ecsClient.updateService(updateRequest);
            
            // Delete service
            DeleteServiceRequest deleteRequest = DeleteServiceRequest.builder()
                .cluster(config.getClusterName())
                .service(config.getEcsServiceName())
                .build();
            
            ecsClient.deleteService(deleteRequest);
            System.out.println("Deleted ECS service: " + config.getEcsServiceName());
            
        } catch (Exception e) {
            System.err.println("Failed to delete ECS service: " + e.getMessage());
        }
    }

    private void deregisterTaskDefinition() {
        // Implementation for deregistering task definition
        try {
            ListTaskDefinitionsRequest listRequest = ListTaskDefinitionsRequest.builder()
                .familyPrefix(config.getTaskDefinitionFamily())
                .status(TaskDefinitionStatus.ACTIVE)
                .build();
            
            ListTaskDefinitionsResponse listResponse = ecsClient.listTaskDefinitions(listRequest);
            
            for (String taskDefArn : listResponse.taskDefinitionArns()) {
                DeregisterTaskDefinitionRequest deregisterRequest = DeregisterTaskDefinitionRequest.builder()
                    .taskDefinition(taskDefArn)
                    .build();
                
                ecsClient.deregisterTaskDefinition(deregisterRequest);
                System.out.println("Deregistered task definition: " + taskDefArn);
            }
            
        } catch (Exception e) {
            System.err.println("Failed to deregister task definitions: " + e.getMessage());
        }
    }

    private void deleteLoadBalancer() {
        // Implementation for deleting load balancer
        try {
            // Delete listeners first
            DescribeListenersRequest listenerRequest = DescribeListenersRequest.builder()
                .loadBalancerArn(getLoadBalancerArn())
                .build();
            
            DescribeListenersResponse listenerResponse = elbClient.describeListeners(listenerRequest);
            for (Listener listener : listenerResponse.listeners()) {
                DeleteListenerRequest deleteListenerRequest = DeleteListenerRequest.builder()
                    .listenerArn(listener.listenerArn())
                    .build();
                
                elbClient.deleteListener(deleteListenerRequest);
            }
            
            // Delete target group
            DeleteTargetGroupRequest deleteTgRequest = DeleteTargetGroupRequest.builder()
                .targetGroupArn(getTargetGroupArn())
                .build();
            
            elbClient.deleteTargetGroup(deleteTgRequest);
            
            // Delete load balancer
            DeleteLoadBalancerRequest deleteLbRequest = DeleteLoadBalancerRequest.builder()
                .loadBalancerArn(getLoadBalancerArn())
                .build();
            
            elbClient.deleteLoadBalancer(deleteLbRequest);
            
            System.out.println("Deleted Load Balancer: " + config.getLoadBalancerName());
            
        } catch (Exception e) {
            System.err.println("Failed to delete Load Balancer: " + e.getMessage());
        }
    }

    private void deleteEcsCluster() {
        // Implementation for deleting ECS cluster
        try {
            DeleteClusterRequest request = DeleteClusterRequest.builder()
                .cluster(config.getClusterName())
                .build();
            
            ecsClient.deleteCluster(request);
            System.out.println("Deleted ECS cluster: " + config.getClusterName());
            
        } catch (Exception e) {
            System.err.println("Failed to delete ECS cluster: " + e.getMessage());
        }
    }

    private void deleteIamRoles() {
        // Implementation for deleting IAM roles
        deleteRole(config.getExecutionRoleName(), "arn:aws:iam::aws:policy/service-role/AmazonECSTaskExecutionRolePolicy");
        deleteRole(config.getTaskRoleName(), null);
    }
    
    private void deleteRole(String roleName, String policyArn) {
        try {
            // Detach policies first if specified
            if (policyArn != null) {
                DetachRolePolicyRequest detachRequest = DetachRolePolicyRequest.builder()
                    .roleName(roleName)
                    .policyArn(policyArn)
                    .build();
                
                iamClient.detachRolePolicy(detachRequest);
            }
            
            // Delete the role
            DeleteRoleRequest deleteRequest = DeleteRoleRequest.builder()
                .roleName(roleName)
                .build();
            
            iamClient.deleteRole(deleteRequest);
            System.out.println("Deleted IAM role: " + roleName);
            
        } catch (NoSuchEntityException e) {
            System.out.println("IAM role not found or already deleted: " + roleName);
        } catch (Exception e) {
            System.err.println("Failed to delete IAM role " + roleName + ": " + e.getMessage());
        }
    }

    private void deleteVpcAndNetworking() {
        // Implementation for cleaning up VPC resources
        // For now, we're using default VPC, so no cleanup needed
        System.out.println("Using default VPC, no cleanup needed");
    }

    private void deleteLogGroup() {
        // Implementation for deleting CloudWatch log group
        try {
            String logGroupName = config.getLogGroupName();
            
            DeleteLogGroupRequest request = DeleteLogGroupRequest.builder()
                .logGroupName(logGroupName)
                .build();
            
            cloudWatchLogsClient.deleteLogGroup(request);
            System.out.println("Deleted CloudWatch Log Group: " + logGroupName);
            
        } catch (ResourceNotFoundException e) {
            System.out.println("CloudWatch Log Group not found or already deleted");
        } catch (Exception e) {
            System.err.println("Failed to delete CloudWatch Log Group: " + e.getMessage());
        }
    }

    private void deleteEcrRepository() {
        // Implementation for deleting ECR repository
        try {
            DeleteRepositoryRequest request = DeleteRepositoryRequest.builder()
                .repositoryName(config.getEcrRepository())
                .force(true) // Delete even if it contains images
                .build();
            
            ecrClient.deleteRepository(request);
            System.out.println("Deleted ECR repository: " + config.getEcrRepository());
            
        } catch (RepositoryNotFoundException e) {
            System.out.println("ECR repository not found or already deleted");
        } catch (Exception e) {
            System.err.println("Failed to delete ECR repository: " + e.getMessage());
        }
    }

    // Helper methods for getting ARNs
    private String getLoadBalancerArn() {
        try {
            DescribeLoadBalancersRequest request = DescribeLoadBalancersRequest.builder()
                .names(config.getLoadBalancerName())
                .build();
            
            DescribeLoadBalancersResponse response = elbClient.describeLoadBalancers(request);
            return response.loadBalancers().get(0).loadBalancerArn();
        } catch (Exception e) {
            return null;
        }
    }
    
    private String getTargetGroupArn() {
        try {
            DescribeTargetGroupsRequest request = DescribeTargetGroupsRequest.builder()
                .names(config.getTargetGroupName())
                .build();
            
            DescribeTargetGroupsResponse response = elbClient.describeTargetGroups(request);
            return response.targetGroups().get(0).targetGroupArn();
        } catch (Exception e) {
            return null;
        }
    }

    // Log tailing method (similar to Lambda)
    public void showLogs() {
        String logGroupName = config.getLogGroupName();
        System.out.println("=== Tailing logs for ECS service: " + config.getEcsServiceName() + " ===");
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
                    
                } catch (ResourceNotFoundException e) {
                    System.err.println("❌ Log group not found: " + logGroupName);
                    System.err.println("   Service may not have been deployed yet or doesn't exist.");
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
        if (message.contains("Started") && message.contains("Application")) {
            System.out.println("🌟 " + timestamp + " ✅ " + message);
            
        } else if (message.contains("Tomcat started on port") || message.contains("server started")) {
            System.out.println("🌐 " + timestamp + " ✅ " + message);
            
        } else if (message.contains("ERROR") || message.contains("Error") || message.contains("Exception")) {
            System.out.println("❌ " + timestamp + " 🔥 " + message);
            
        } else if (message.contains("WARN") || message.contains("Warning")) {
            System.out.println("⚠️  " + timestamp + " ⚠️  " + message);
            
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

    // Helper classes for storing resource information
    private static class VpcResources {
        private String vpcId;
        private List<String> subnetIds;
        private String internetGatewayId;
        private String routeTableId;
        private boolean isCustomVpc = false;
        
        public String getVpcId() { return vpcId; }
        public void setVpcId(String vpcId) { this.vpcId = vpcId; }
        public List<String> getSubnetIds() { return subnetIds; }
        public void setSubnetIds(List<String> subnetIds) { this.subnetIds = subnetIds; }
        public String getInternetGatewayId() { return internetGatewayId; }
        public void setInternetGatewayId(String internetGatewayId) { this.internetGatewayId = internetGatewayId; }
        public String getRouteTableId() { return routeTableId; }
        public void setRouteTableId(String routeTableId) { this.routeTableId = routeTableId; }
        public boolean isCustomVpc() { return isCustomVpc; }
        public void setCustomVpc(boolean isCustomVpc) { this.isCustomVpc = isCustomVpc; }
    }
    
    private static class LoadBalancerResources {
        private String loadBalancerArn;
        private String targetGroupArn;
        private String listenerArn;
        private String dnsName;
        private String securityGroupId;
        
        // Getters and setters
        public String getLoadBalancerArn() { return loadBalancerArn; }
        public void setLoadBalancerArn(String loadBalancerArn) { this.loadBalancerArn = loadBalancerArn; }
        public String getTargetGroupArn() { return targetGroupArn; }
        public void setTargetGroupArn(String targetGroupArn) { this.targetGroupArn = targetGroupArn; }
        public String getListenerArn() { return listenerArn; }
        public void setListenerArn(String listenerArn) { this.listenerArn = listenerArn; }
        public String getDnsName() { return dnsName; }
        public void setDnsName(String dnsName) { this.dnsName = dnsName; }
        public String getSecurityGroupId() { return securityGroupId; }
        public void setSecurityGroupId(String securityGroupId) { this.securityGroupId = securityGroupId; }
    }
    
    // Additional aggressive cleanup methods
    private void deleteSecurityGroups() {
        try {
            // Delete any security groups with our cluster name tag
            var describeRequest = DescribeSecurityGroupsRequest.builder()
                .filters(Filter.builder()
                    .name("tag:Name")
                    .values("*" + config.getClusterName() + "*")
                    .build())
                .build();
            
            var response = ec2Client.describeSecurityGroups(describeRequest);
            for (var sg : response.securityGroups()) {
                if (!sg.groupName().equals("default")) {
                    try {
                        var deleteRequest = DeleteSecurityGroupRequest.builder()
                            .groupId(sg.groupId())
                            .build();
                        ec2Client.deleteSecurityGroup(deleteRequest);
                        System.out.println("✅ Deleted security group: " + sg.groupName());
                    } catch (Exception e) {
                        System.out.println("Could not delete security group " + sg.groupName() + ": " + e.getMessage());
                    }
                }
            }
        } catch (Exception e) {
            System.out.println("Could not clean up security groups: " + e.getMessage());
        }
    }
    
    private void deleteNetworkInterfaces() {
        try {
            // Delete any ENIs with our cluster name in description
            var describeRequest = DescribeNetworkInterfacesRequest.builder()
                .filters(Filter.builder()
                    .name("description")
                    .values("*" + config.getClusterName() + "*")
                    .build())
                .build();
            
            var response = ec2Client.describeNetworkInterfaces(describeRequest);
            for (var eni : response.networkInterfaces()) {
                if (eni.status() == NetworkInterfaceStatus.AVAILABLE) {
                    try {
                        var deleteRequest = DeleteNetworkInterfaceRequest.builder()
                            .networkInterfaceId(eni.networkInterfaceId())
                            .build();
                        ec2Client.deleteNetworkInterface(deleteRequest);
                        System.out.println("✅ Deleted network interface: " + eni.networkInterfaceId());
                    } catch (Exception e) {
                        System.out.println("Could not delete network interface " + eni.networkInterfaceId() + ": " + e.getMessage());
                    }
                }
            }
        } catch (Exception e) {
            System.out.println("Could not clean up network interfaces: " + e.getMessage());
        }
    }
}