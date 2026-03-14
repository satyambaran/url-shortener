@description('Location for all resources')
param location string = resourceGroup().location

@description('Postgres flexible server name')
param postgresServerName string = 'urlshortener-postgres'

@description('Administrator username (no @)')
param administratorLogin string = 'pgadmin'

@secure()
@description('Administrator password')
param administratorPassword string

@description('SKU name (e.g., Standard_D2s_v3)')
param skuName string = 'Standard_D4s_v3'

@description('Storage size in GB')
param storageSizeGB int = 50

@description('Backup retention days')
param backupRetentionDays int = 7

@description('Enable geo-redundant backups (true/false)')
param geoRedundantBackup bool = true

@description('Delegated subnet resource id for VNet integration (Postgres Flexible Server requires its own subnet)')
param delegatedSubnetResourceId string

@description('Postgres version')
param version string = '15'

// PgBouncer is recommended to be run in AKS (Deployment/Service). This parameter is informational only and emitted as an output
param pgbouncerMaxConnections int = 100

var serverName = postgresServerName

resource postgresServer 'Microsoft.DBforPostgreSQL/flexibleServers@2022-12-01' = {
  name: serverName
  location: location
  sku: {
    name: 'Standard_D4s_v3'
    tier: 'GeneralPurpose'
    family: 'Gen5'
    capacity: 2
  }
  kind: 'postgresql'
  properties: {
    version: version
    storage: {
      storageSizeGB: storageSizeGB
    }
    network: {
      delegatedSubnetResourceId: delegatedSubnetResourceId
      privateNetworkAccess: 'Enabled'
    }
    highAvailability: {
      mode: 'Zone'
    }
    backup: {
      backupRetentionDays: backupRetentionDays
      geoRedundantBackup: geoRedundantBackup
    }
    administratorLogin: administratorLogin
    administratorLoginPassword: administratorPassword
    sslEnforcement: 'Enabled'
  }
  tags: {
    workload: 'url-shortener'
    managedBy: 'fenster'
  }
}

// Output connection details (note: secure secrets are not printed)
output postgresPrivateFqdn string = postgresServer.properties.fullyQualifiedDomainName
output pgbouncerRecommendedConnections int = pgbouncerMaxConnections
output postgresResourceId string = postgresServer.id
