@description('Location for Redis')
param location string = resourceGroup().location

@description('Redis cache name')
param redisName string = 'urlshortener-redis'

@description('SKU family (P for Premium)')
param skuFamily string = 'P'

@description('SKU capacity (1 => ~1GB per shard for Premium)')
param skuCapacity int = 1

@description('Number of shards (cluster nodes)')
param shardCount int = 6

@description('Subnet resource id for VNet integration (delegated subnet)')
param subnetResourceId string

@description('Enable persistence for Redis (AOF/RDB)')
param enablePersistence bool = true

resource redis 'Microsoft.Cache/Redis@2021-06-01' = {
  name: redisName
  location: location
  sku: {
    name: 'Premium'
    family: skuFamily
    capacity: skuCapacity
  }
  properties: {
    enableNonSslPort: false
    shardCount: shardCount
    minimumTlsVersion: '1.2'
    redisConfiguration: {
      'appendonly' : enablePersistence ? 'yes' : 'no'
    }
    subnetId: subnetResourceId
    clusteringPolicy: 'enterprise'
    persistence: enablePersistence ? {
      aofEnabled: true
      aofFrequency: 'everysec'
    } : null
  }
  tags: {
    workload: 'url-shortener'
    managedBy: 'fenster'
  }
}

output redisHostName string = redis.properties.hostName
output redisPort int = redis.properties.port
output redisResourceId string = redis.id
