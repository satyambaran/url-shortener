@description('Deployment orchestrator for managed Postgres and Redis')
param location string = resourceGroup().location

@description('Postgres delegated subnet resource id')
param postgresSubnetId string

@description('Redis delegated subnet resource id')
param redisSubnetId string

@secure()
param postgresAdminPassword string

module postgresModule 'postgres-managed.bicep' = {
  name: 'postgresModule'
  scope: resourceGroup()
  params: {
    location: location
    delegatedSubnetResourceId: postgresSubnetId
    administratorPassword: postgresAdminPassword
  }
}

module redisModule 'redis-managed.bicep' = {
  name: 'redisModule'
  scope: resourceGroup()
  params: {
    location: location
    subnetResourceId: redisSubnetId
  }
}

output postgresFqdn string = postgresModule.outputs.postgresPrivateFqdn
output redisHost string = redisModule.outputs.redisHostName
