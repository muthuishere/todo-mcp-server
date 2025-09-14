# Cloud Deployment Guide

This project supports deployment to multiple cloud providers with automated infrastructure setup. Each provider has its own set of tasks for setup, deployment, logs, and cleanup.

## 🛠️ Prerequisites & Installation

Before using any cloud provider, you need to install the respective CLI tools and authenticate.

### AWS CLI Installation & Setup
```bash
# Install AWS CLI using Homebrew (macOS)
brew install awscli

# Configure AWS credentials
aws configure
# Enter: Access Key ID, Secret Access Key, Default region (e.g., us-east-1), Output format (json)

# Verify authentication
aws sts get-caller-identity

# Ensure Docker is installed
brew install docker
# Or install Docker Desktop from https://docker.com
```

### Google Cloud CLI Installation & Setup
```bash
# Install Google Cloud CLI using Homebrew (macOS)
brew install google-cloud-sdk

# Login to Google Cloud
gcloud auth login

# Set up Application Default Credentials (required for deployment)
gcloud auth application-default login

# Set your project (replace with your actual project ID)
gcloud config set project YOUR_PROJECT_ID

# Verify authentication and project
gcloud auth list
gcloud config get-value project
```

### Azure CLI Installation & Setup
```bash
# Install Azure CLI using Homebrew (macOS)
brew install azure-cli

# Login to Azure
az login

# Install Container Apps extension (required)
az extension add --name containerapp

# Verify authentication
az account show

# Set subscription if you have multiple (optional)
az account set --subscription "Your Subscription Name"
```

### Docker Installation
```bash
# Install Docker using Homebrew (macOS)
brew install docker

# Or install Docker Desktop from https://docker.com
# Ensure Docker is running before deployment
docker --version
```

## 🌟 Current Status

| Provider | Status | Setup | Deploy | Logs | Destroy | Notes |
|----------|--------|-------|--------|------|---------|-------|
| **AWS Fargate** | ✅ Working | ✅ | ✅ | ✅ | ✅ | Fully functional with ECS, ECR, and CloudWatch |
| **AWS Lambda** | ❌ Not Working | ❌ | ❌ | ❌ | ❌ | Implementation incomplete |
| **GCP Cloud Run** | ⚠️ Partial | ✅ | ✅ | ⚠️ | ✅ | Logs have streaming issues |
| **Azure Container Apps** | ✅ Working | ✅ | ✅ | ✅ | ✅ | Fully functional |

## 📋 Available Tasks

### AWS Fargate (Fully Working)
```bash
# Setup infrastructure (VPC, ECS cluster, ECR repository, IAM roles)
task infra:aws-fargate:setup

# Deploy application (build Docker image, push to ECR, deploy to ECS)
task infra:aws-fargate:deploy

# Show logs (CloudWatch logs with real-time tailing)
task infra:aws-fargate:logs

# Destroy infrastructure (cleanup all resources)
task infra:aws-fargate:destroy
```

### AWS Lambda (Not Working)
```bash
# These tasks exist but are not functional
task infra:aws-lambda:setup    # ❌ Not implemented
task infra:aws-lambda:deploy   # ❌ Not implemented  
task infra:aws-lambda:logs     # ❌ Not implemented
task infra:aws-lambda:destroy  # ❌ Not implemented
```

### GCP Cloud Run (Mostly Working)
```bash
# Setup infrastructure (Artifact Registry, enable APIs)
task infra:gcp-cloudrun:setup

# Deploy application (build Docker image, push to Artifact Registry, deploy to Cloud Run)
task infra:gcp-cloudrun:deploy

# Show logs (⚠️ has issues with real-time streaming)
task infra:gcp-cloudrun:logs

# Destroy infrastructure (cleanup all resources)
task infra:gcp-cloudrun:destroy
```

### Azure Container Apps (Fully Working)
```bash
# Setup infrastructure (Container Registry, Container Apps Environment)
task infra:azure-containerapps:setup

# Deploy application (build Docker image, push to ACR, deploy to Container Apps)
task infra:azure-containerapps:deploy

# Show logs (Azure CLI logs with real-time streaming)
task infra:azure-containerapps:logs

# Destroy infrastructure (cleanup all resources)
task infra:azure-containerapps:destroy
```

## 🚀 When to Use Each Provider for Serverless MCP Deployment

### Cost Comparison for MCP Servers

Since MCP (Model Context Protocol) servers are typically used on-demand by AI clients, **serverless deployment is the ideal architecture**. Here's how each provider fits this use case:

### 💰 GCP Cloud Run - **RECOMMENDED** for MCP Servers
**Why Perfect for MCP:**
- **True serverless**: Scales to zero when not used by AI clients
- **Pay-per-request**: Only pay when MCP calls are made
- **Cold starts acceptable**: MCP clients can handle 1-2 second startup delays
- **Extremely cost-effective**: Most MCP servers cost under $5/month

**Pricing for MCP Usage:**
- **When idle**: $0.00 (perfect for personal MCP servers)
- **Typical MCP usage**: $1-10/month (thousands of AI interactions)
- **Heavy usage**: $10-50/month (constant AI assistant usage)

**Use GCP Cloud Run when:**
- ✅ Building personal or small team MCP servers
- ✅ Cost is important (most common case)
- ✅ Usage is sporadic (AI assistants used occasionally)
- ✅ Can tolerate brief cold starts between MCP sessions
- ✅ Simple deployment preferred

### 🏢 AWS Fargate - For Enterprise MCP Deployments
**Why Less Ideal for Most MCP Cases:**
- **Always-running**: Wastes money when MCP server isn't being used
- **Higher baseline cost**: Minimum $15-30/month even if rarely used
- **Over-engineered**: Most MCP servers don't need enterprise features

**Use AWS Fargate when:**
- ✅ Enterprise environment with constant AI usage
- ✅ Multiple teams using MCP servers simultaneously
- ✅ Need integration with existing AWS infrastructure
- ✅ Budget allows for always-on costs ($30-100/month)
- ✅ Require instant response times (no cold starts)

### 🔷 Azure Container Apps - Good Middle Ground
**For Microsoft Ecosystem MCP:**
- **Scale-to-zero available**: Can be cost-effective like Cloud Run
- **Integration**: Works well if already using Azure/Microsoft stack
- **Moderate cost**: $2-20/month for typical MCP usage

**Use Azure Container Apps when:**
- ✅ Already using Microsoft/Azure ecosystem
- ✅ Need more features than basic serverless
- ✅ Want balance between cost and capabilities
- ✅ Building multiple interconnected MCP servers

### 📊 MCP Server Cost Reality Check

| Usage Pattern | GCP Cloud Run | AWS Fargate | Azure Container Apps |
|---------------|---------------|-------------|---------------------|
| **Personal MCP server** | $0-3/month | $15-25/month | $1-5/month |
| **Small team usage** | $3-15/month | $30-50/month | $5-20/month |
| **Heavy AI workload** | $15-50/month | $50-100/month | $20-60/month |
| **Enterprise/Always-on** | $20-80/month | $50-150/month | $30-100/month |

### 🎯 **MCP Server Deployment Recommendation**

**🥇 First Choice: GCP Cloud Run**
- **Perfect for 90% of MCP use cases**
- **Scales to zero = minimal costs**
- **MCP clients handle cold starts well**
- **Simple deployment and management**

**🥈 Second Choice: Azure Container Apps**
- **If you're in Microsoft ecosystem**
- **Need more advanced scaling features**
- **Still supports scale-to-zero**

**🥉 Third Choice: AWS Fargate**
- **Only for enterprise scenarios**
- **When cost isn't a primary concern**
- **Need always-on performance**

### 💡 Key Insight for MCP Servers

**MCP servers are perfect serverless candidates because:**
- Used intermittently by AI clients
- Can handle cold start delays (1-2 seconds)
- Don't need always-on availability
- Cost-effectiveness is usually important for developers
- Simple deployment and maintenance preferred

**Bottom line:** Unless you have specific enterprise requirements, **GCP Cloud Run is the clear winner for MCP server deployment** due to its true serverless nature and cost-effectiveness.

## 🔧 Configuration Files

Each provider uses its own configuration file in the `config/` directory:

- **AWS Fargate**: `config/fargateconfig.yaml`
- **AWS Lambda**: `config/lambdaconfig.yaml` (not functional)
- **GCP Cloud Run**: `config/cloudrunconfig.yaml`
- **Azure Container Apps**: `config/azurecontainerappsconfig.yaml`

## 📝 Deployment Process

### Standard Workflow for Working Providers:

1. **Setup** - Creates cloud infrastructure (registries, networking, IAM)
2. **Deploy** - Builds Docker image, pushes to registry, deploys service
3. **Logs** - Shows application logs with real-time updates
4. **Destroy** - Cleans up all created resources

### Example: Deploy to AWS Fargate
```bash
# 1. Setup infrastructure
task infra:aws-fargate:setup

# 2. Deploy application  
task infra:aws-fargate:deploy

# 3. View logs (in separate terminal)
task infra:aws-fargate:logs

# 4. Cleanup when done
task infra:aws-fargate:destroy
```

## 🐛 Known Issues and Workarounds

### GCP Cloud Run Logs
**Issue**: `gcloud beta run services logs tail` command has intermittent streaming failures
```
error receiving response: rpc error: code = Internal desc = Internal error encountered.
```

**Current Workaround**: 
- Shows last 50 log entries initially
- Polls for new logs every 10 seconds
- May show some duplicate entries but won't miss new logs

**Alternative Manual Commands**:
```bash
# View recent logs
gcloud beta run services logs read todoapp-cloudrun --project=myspringai-test --region=us-central1 --limit=50

# Try tail (may fail)
gcloud beta run services logs tail todoapp-cloudrun --project=myspringai-test --region=us-central1
```

### AWS Lambda
**Issue**: Implementation is incomplete
**Status**: Tasks exist but functionality not implemented
**Recommendation**: Use AWS Fargate for AWS deployments

##  Configuration Files

Each provider uses its own configuration file in the `config/` directory. You need to customize these files with your specific settings.

### AWS Fargate Configuration (`config/fargateconfig.yaml`)
```yaml
# AWS Fargate Configuration
region: "us-east-1"                    # Change to your preferred AWS region

# Service Configuration
serviceName: "todo-mcp-server"         # Your service name

# Container Configuration
dockerfilePath: "../Dockerfile"        # Path to Dockerfile
environmentFile: "../.env"             # Environment variables file
containerPort: 8080                    # Port your app listens on

# Resource Configuration
cpu: 512                              # CPU units (256, 512, 1024, 2048, 4096)
memory: 1024                          # Memory in MB (512, 1024, 2048, etc.)
desiredCount: 1                       # Number of running tasks

# Load Balancer Configuration
healthCheckPath: "/api/health"         # Health check endpoint
healthCheckIntervalSeconds: 30         # Health check frequency

# Deployment Settings
deploymentTimeoutMinutes: 10          # Max deployment time
```

**Key Settings to Modify:**
- `region`: Your preferred AWS region (e.g., us-west-2, eu-west-1)
- `serviceName`: Unique name for your service
- `cpu/memory`: Based on your application requirements
- `desiredCount`: Number of instances to run

### GCP Cloud Run Configuration (`config/cloudrunconfig.yaml`)
```yaml
projectId: "your-gcp-project-id"       # REQUIRED: Your GCP project ID
region: "us-central1"                  # Change to your preferred region
serviceName: "todoapp-cloudrun"        # Your service name
dockerfilePath: "Dockerfile"           # Path to Dockerfile
environmentFile: ".env"                # Environment variables file
containerPort: 8080                    # Port your app listens on

# Resource Configuration
cpu: "1"                              # CPU cores (0.25, 0.5, 1, 2, 4)
memory: "512Mi"                       # Memory (128Mi, 256Mi, 512Mi, 1Gi, 2Gi, etc.)
minInstances: 0                       # Minimum instances (0 for scale-to-zero)
maxInstances: 10                      # Maximum instances
concurrency: 80                       # Concurrent requests per instance
timeout: 300                          # Request timeout in seconds

# Access Configuration
allowUnauthenticated: true            # Allow public access without authentication

# Environment Variables
environmentVariables:
  SPRING_PROFILES_ACTIVE: "streamable"
  # Add your environment variables here
```

**Key Settings to Modify:**
- `projectId`: **REQUIRED** - Your GCP project ID (get with `gcloud config get-value project`)
- `region`: Your preferred GCP region (e.g., us-west1, europe-west1)
- `serviceName`: Unique name for your Cloud Run service
- `allowUnauthenticated`: Set to `false` if you want authenticated access only

### Azure Container Apps Configuration (`config/azurecontainerappsconfig.yaml`)
```yaml
# Azure Container Apps Configuration
# Uses Consumption Plan with automatic scaling

# Core Azure configuration
location: "East US"                    # Azure region
serviceName: "todo-mcp-server"         # Your service name

# Container configuration
dockerfilePath: "../Dockerfile"        # Path to Dockerfile
environmentFile: "../.env"             # Environment variables file
containerPort: 8080                    # Port your app listens on

# Resource configuration
cpu: "0.25"                           # CPU cores (0.25, 0.5, 0.75, 1.0, 1.25, 1.5, 1.75, 2.0)
memory: "0.5Gi"                       # Memory (0.5Gi, 1.0Gi, 1.5Gi, 2.0Gi, 3.0Gi, 3.5Gi, 4.0Gi)
minReplicas: 0                        # Minimum replicas (0 for scale-to-zero)
maxReplicas: 1                        # Maximum replicas

# Health check configuration
healthCheckPath: "/api/health"         # Health check endpoint
healthCheckIntervalSeconds: 30         # Health check frequency
deploymentTimeoutMinutes: 10          # Max deployment time

# Note: The following resources are automatically named based on serviceName:
# - Resource Group: {serviceName}-rg
# - Container App: {serviceName}-app
# - Environment: {serviceName}-env
# - Registry: {serviceName}registry (alphanumeric only)
# - Workspace: {serviceName}-workspace
```

**Key Settings to Modify:**
- `location`: Your preferred Azure region (e.g., "West US 2", "North Europe")
- `serviceName`: Unique name for your service (used for all resource naming)
- `cpu/memory`: Based on your application requirements
- `maxReplicas`: Maximum number of instances

## 🔧 Getting Your Configuration Values

### AWS - Get Account Information
```bash
# Get your AWS account ID and region
aws sts get-caller-identity
aws configure get region

# List available regions
aws ec2 describe-regions --output table
```

### GCP - Get Project Information
```bash
# Get your current project ID
gcloud config get-value project

# List all your projects
gcloud projects list

# List available regions for Cloud Run
gcloud run regions list
```

### Azure - Get Subscription Information
```bash
# Get subscription details
az account show

# List available locations
az account list-locations --output table
```

## 📖 MCP Server Configuration

After successful deployment, each provider outputs MCP client configuration:

```json
{
  "todoapp-service": {
    "type": "http",
    "url": "https://your-service-url/mcp"
  }
}
```

Add this configuration to your MCP client to connect to the deployed service.