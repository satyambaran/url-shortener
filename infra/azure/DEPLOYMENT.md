Deployment instructions for managed Azure resources (PostgreSQL Flexible Server & Redis Premium)

Prerequisites:
- Azure CLI installed and logged in (az login)
- An existing Resource Group in the same region as your AKS cluster
- Subnets created for PostgreSQL Flexible Server and Redis with the required service delegation and NSG rules
- If using AKS VNet, ensure subnets allow service endpoints and are isolated as required

Quick deploy (resource group-scoped):

1. Create a resource group (if not present):
   az group create -n my-rg -l eastus

2. Deploy using the main template in this folder (adjust params as needed):
   az deployment group create \
     --resource-group my-rg \
     --template-file infra/azure/main-managed.bicep \
     --parameters location=eastus \
                 postgresSubnetId=/subscriptions/<sub>/resourceGroups/<rg>/providers/Microsoft.Network/virtualNetworks/<vnet>/subnets/<postgres-subnet> \
                 redisSubnetId=/subscriptions/<sub>/resourceGroups/<rg>/providers/Microsoft.Network/virtualNetworks/<vnet>/subnets/<redis-subnet> \
                 postgresAdminPassword=<secure-password>

Notes & Recommendations:
- PgBouncer: Azure Database for PostgreSQL Flexible Server does not provide built-in PgBouncer. Run PgBouncer as a Deployment in AKS (use "pgbouncer" image) and configure max client connections to 100. The template emits a recommended connection value as an output.
- VNet integration: Both Postgres Flexible Server and Redis Premium must be deployed into delegated subnets. Create those subnets beforehand and delegate them to the respective services.
- Redis sharding: This template configures shardCount=6 and capacity=1 (approx 1GB per shard). Validate available SKUs/capacities in your region prior to deploy.
- Backups: Geo-redundant backups are enabled by default in the Postgres template; verify retention/restore procedures in Azure Portal.
- Adjust SKUs/capacity to match production performance and cost requirements.
