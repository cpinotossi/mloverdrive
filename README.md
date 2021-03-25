# az-aks-sb-java

Example of Azure AKS in combination with Azure Service Bus using Java

## Clone from Git

~~~~pwsh
PS C:\> git clone https://github.com/cpinotossi/az-aks-sb-java.git
~~~~

Switch into subdirectory sbapp

~~~~pwsh
PS C:\> cd sbapp
~~~~

## Package Maven Project

~~~~pwsh
PS C:\sbapp> mvn clean package
~~~~

## Create Azure Environment

Create an Azure Resource Group. In our case we already create one called "mloverdrive-rg".

Build via ARM Template:

Replace the defaultValue "mloverdrive" of the parameter "prefix" inside the ARM Template (sbapp/arm/deploy.json):

~~~~json
"prefix": {
    "type": "string",
    "defaultValue": "mloverdrive",
    "metadata": {
        "description": "Name of the resourceGroup to create"
    }
},
~~~~

Start deployment:

~~~~pwsh
PS C:\sbapp> az deployment group create --resource-group mloverdrive-rg --mode Incremental --name create-mloverdrive --template-file ./arm/deploy.json | Out-File -FilePath .\arm.deploy.output.json
~~~~

ARM Output Variables have been written to sbapp\arm.deploy.output.json after deployment finished.

~~~~pwsh
PS C:\sbapp> ls .\arm.deploy.output.json

    Directory: C:\Users\chpinoto\workspace\az-aks-sb-java\sbapp

Mode                 LastWriteTime         Length Name
----                 -------------         ------ ----
-a---           3/17/2021    21:33          31096 arm.deploy.output.json
~~~~

## Update Role Assignment between AKS and Azure Service Bus

## Service Bus Role Assignment

Azure Service Bus Data Owner = 090c5cfd-751d-490a-894a-3ce6f1109419
~~~~pwsh
Retrieve Kubernetes Managed Id

~~~~pwsh
az ad sp list --all --filter "servicePrincipalType eq 'ManagedIdentity' and DisplayName eq 'mloverdrive-aks'" --query [].objectId
[
  "bb2b5523-73cb-4597-a32e-8a2d48d76820"
]
~~~~

Retrieve Service Bus Owner Role

~~~~pwsh
az role definition list --name "Azure Service Bus Data Owner" --query [].name
[
  "090c5cfd-751d-490a-894a-3ce6f1109419"
]
~~~~

Assign Kubernetes Reader Role on Azure Service Bus

~~~~pwsh
az role assignment create --role 090c5cfd-751d-490a-894a-3ce6f1109419 --assignee bb2b5523-73cb-4597-a32e-8a2d48d76820 --scope /subscriptions/<YOUR-SUBSCRIPTION-ID>/resourceGroups/<YOUR-RESOURCGROUP-NAME>/providers/Microsoft.ServiceBus/namespaces/mloverdrive-servicebusnamespace
~~~~

## Verify AKS Cluster

List the nodepools:

~~~~pwsh
PS C:\sbapp> az aks nodepool list -g mloverdrive-rg --cluster-name mloverdrive-aks --output table
Name    OsType    KubernetesVersion    VmSize           Count    MaxPods    ProvisioningState    Mode
------  --------  -------------------  ---------------  -------  ---------  -------------------  ------
keda    Linux     1.19.7               Standard_F8s_v2  2        10         Succeeded            User
system  Linux     1.19.7               Standard_F8s_v2  3        10         Succeeded            System
worker  Linux     1.19.7               Standard_D2_v4   7        10         Succeeded            User
~~~~

## Update Parameters

Update Docker-, Kubernetes-, VisualStudioCode-Configuration:

~~~~pwsh
PS C:\sbapp> node .\update.js
Update:./Dockerfile
Update:./jmeter/test.properties
Update:../.vscode/launch.json
Update:./k8s/deploy.yaml
~~~~

## Deploy to AKS
 
### Create Docker Image

Create local Docker Image:

~~~~pwsh
PS C:\sbapp>  docker build . -t mloverdrive.azurecr.io/mlover-distroless:v1
~~~~

## Log into the Azure Container Registration

Log into the Azure Container Registry

~~~~pwsh
PS C:\sbapp> az acr login --name mloverdrive
~~~~

## Push images to registry

~~~~pwsh
PS C:\sbapp> docker push mloverdrive.azurecr.io/mlover-distroless:v1
~~~~

## Connect to cluster using kubectl

~~~~pwsh
PS C:\sbapp> az aks get-credentials --resource-group mloverdrive-rg --name mloverdrive-aks
~~~~

## Update Role Assignment between ACI and AKS via Azure CLI

This will allow AKS to access ACR to pull images from.

TODO: Not working as expected

~~~~pwsh
PS C:\\sbapp> az aks update -n mloverdrive-aks -g mloverdrive-rg --attach-acr mloverdrive
~~~~

Based on https://docs.microsoft.com/en-us/azure/aks/cluster-container-registry-integration#configure-acr-integration-for-existing-aks-clusters

## Deploy

Create new Namespace at Kubernetes

~~~~pwsh
PS C:\sbapp> kubectl create namespace mloverdrive
~~~~

Create Kubernetes Secret

~~~~pwsh
PS C:\sbapp> kubectl apply -f ./k8s/secret.yaml
~~~~

Update containers:image reference inside the deploy.yaml:

~~~~yaml
    spec:
      containers:
        - name: servicebusreceiver
          image: mloverdrive.azurecr.io/mlover-distroless:v1
          imagePullPolicy: Always
          env:
~~~~

Create deployment at Kubernetes

~~~~pwsh
PS C:\sbapp> kubectl apply -f ./k8s/deploy.yaml
~~~~

## Setup KEDA

### Install KEDA via yaml

~~~~pwsh
PS C:\sbapp> kubectl apply -f https://github.com/kedacore/keda/releases/download/v2.2.0/keda-2.2.0.yaml
~~~~

### Deploy Keda Scaler:

~~~~pwsh
kubectl apply -f ./k8s/keda.yaml
~~~~

## How to test

### Expectation

"Deliver Results in below 30 sec for each incoming Event"

We like to verify the worse case scenario in which we need to scale out Nodes (VM). This will be the case when the amount of Nodes will not be sufficient to run the needed amount of Pods.

IMPORTANT: Each Pods needs a full Node, so we are working with an ratio of 1 pod = 1 node.

In our test case we do run 3 Pods in standby mode. Via KEDA we can scale out to up to 100 pods.

Our test will send 9 Event and therefore we will need to scale out our Pods to process the incoming messages as soon as possible.

This will direclty result into scale out of Nodes because of our 1:1 ratio between Pods per Nodes.

IMPORTANT: We would not recommend such a setup for production. At production we shoud consider a rate of pods >> nodes.

#### Node Status

Kubernetes Namespace "mloverdrive" is aligned to the AKS "Nodes Pool" "worker" which defined with an minCount of 3 Nodes:

~~~~json
{
  ..
  "name": "worker",
  "count": 1,
  "enableAutoScaling": true,
  "maxPods": 10,
  "minCount": 3,
  ..
}
~~~~

This can be looked up as follow:

~~~~pwsh
PS C:\Users\chpinoto> az aks nodepool list --resource-group mloverdrive-rg --cluster-name mloverdrive-aks -o table
Name    OsType    KubernetesVersion    VmSize           Count    MaxPods    ProvisioningState    Mode
------  --------  -------------------  ---------------  -------  ---------  -------------------  ------
keda    Linux     1.19.7               Standard_F8s_v2  3        10         Succeeded            User
system  Linux     1.19.7               Standard_F8s_v2  3        10         Succeeded            System
worker  Linux     1.19.7               Standard_D2_v4   3        10         Succeeded            User
~~~~

This can also be verified directly via Kubernetes (kubectl).

NOTE: "aks-worker-*" are the once aligend to the AKS "Nodes Pool" "worker".

~~~~pwsh
PS C:\Users\chpinoto> kubectl -n mloverdrive get nodes
NAME                             STATUS   ROLES   AGE   VERSION
aks-keda-30372806-vmss000000     Ready    agent   12h   v1.19.7
aks-keda-30372806-vmss000001     Ready    agent   12h   v1.19.7
aks-keda-30372806-vmss000002     Ready    agent   12h   v1.19.7
aks-system-30372806-vmss000000   Ready    agent   12h   v1.19.7
aks-system-30372806-vmss000001   Ready    agent   12h   v1.19.7
aks-system-30372806-vmss000002   Ready    agent   12h   v1.19.7
aks-worker-30372806-vmss000000   Ready    agent   12h   v1.19.7
aks-worker-30372806-vmss000004   Ready    agent   10h   v1.19.7
aks-worker-30372806-vmss000005   Ready    agent   10h   v1.19.7
~~~~

#### Pod Status

Our current setup does consider that we can only run 1 Pod per Node.

IMPORTANT: We believe it would make fare more sense to be able to run multiple Pods on a single Node.

Based on our current setup inside the k8s/keda.yaml 3 Pods are running all the time:

~~~~yaml
apiVersion: keda.sh/v1alpha1
kind: ScaledObject
metadata:
  name: mloverdrive-scaledobject
  namespace: mloverdrive # your namespace
spec:
  scaleTargetRef:
    name: servicebusreceiver #name of your deployment
  minReplicaCount: 3 # <== 3 PODS TO RUN ALWAYS
  maxReplicaCount: 100
  pollingInterval: 1
  cooldownPeriod: 120 
~~~~

Or via the corresponding Horizontal Pod Autoscaler [hpa]:

~~~~pwsh
PS C:\Users\chpinoto> kubectl -n mloverdrive get hpa -w
NAME                                REFERENCE                       TARGETS     MINPODS   MAXPODS   REPLICAS   AGE
keda-hpa-mloverdrive-scaledobject   Deployment/servicebusreceiver   0/1 (avg)   3         100       3          11h
~~~~

TODO: Clarify if this statement is correct:
TARGETS: That the current Queue lengts is 0 as we are not sending any requests to the server (the TARGET column shows the average queue length). Which is defined inside the k8s/keda.yaml "messageCount":

~~~~yaml
  - type: azure-servicebus
    metadata:
      # Required: queueName OR topicName and subscriptionName
      queueName: mloverdrive-servicebusqueue
      # Required: Define what Azure Service Bus to authenticate to with Managed Identity
      namespace: mloverdrive-servicebusnamespace
      # Optional
      messageCount: "1" # default 5
    authenticationRef:
      name: azure-servicebus-auth # authenticationRef would need either podIdentity or define a connection parameter
~~~~

Lookup corresponding KEDA scaleobject status:

NOTE: Here you will find all the corresponding settings of your k8s/keda.yaml.

~~~~pwsh
PS C:\Users\chpinoto> kubectl -n mloverdrive get scaledobject -w
NAME                       SCALETARGETKIND      SCALETARGETNAME      MIN   MAX   TRIGGERS           AUTHENTICATION          READY   ACTIVE   AGE
mloverdrive-scaledobject   apps/v1.Deployment   servicebusreceiver   3     100   azure-servicebus   azure-servicebus-auth   True    False    11h
~~~~

This can be looked up directly inside the kubernetes namespace:

~~~~pwsh
PS C:\Users\chpinoto> kubectl -n mloverdrive get pods
NAME                                  READY   STATUS    RESTARTS   AGE
servicebusreceiver-77c478ffcc-7dvkj   1/1     Running   0          32m
servicebusreceiver-77c478ffcc-h8sdg   1/1     Running   0          31m
servicebusreceiver-77c478ffcc-jb6vg   1/1     Running   0          31m
~~~~

### Start Test

Get the logs of one Pod

NOTE: Pod name will differ from the one used here ("servicebusreceiver-77c478ffcc-7dvkj") in your environment.

~~~~pwsh
PS C:\Users\chpinoto> kubectl -n mloverdrive logs servicebusreceiver-77c478ffcc-7dvkj -f
Picked up _JAVA_OPTIONS: -Xmx2g -Xms2g
IsSB:true
SBQN:mloverdrive-servicebusqueue
SOCS:DefaultEnd
SICS:DefaultEnd
SBCS:Endpoint=s
SOCN:output
SICN:input
[main] INFO com.azure.messaging.servicebus.implementation.ServiceBusConnectionProcessor - namespace[] entityPath[mloverdrive-servicebusnamespace.servicebus.windows.net]: Setting next AMQP channel.
[main] INFO com.azure.messaging.servicebus.implementation.ServiceBusConnectionProcessor - namespace[] entityPath[mloverdrive-servicebusnamespace.servicebus.windows.net]: Next AMQP channel received, updating 0 current subscribers
[main] INFO com.azure.messaging.servicebus.ServiceBusClientBuilder - # of open clients with shared connection: 1
...
[reactor-executor-1] INFO com.azure.core.amqp.implementation.handler.ReceiveLinkHandler - onLinkRemoteOpen connectionId[MF_afff94_1616666694654], entityPath[mloverdrive-servicebusqueue], linkName[mloverdrive-servicebusqueue_aff359_1616666694740], remoteSource[Source{address='mloverdrive-servicebusqueue', durable=NONE, expiryPolicy=SESSION_END, timeout=0, dynamic=false, dynamicNodeProperties=null, distributionMode=null, filter=null, defaultOutcome=null, outcomes=null, capabilities=null}]
~~~~

Run a simple test

NOTE: By default test will be run with 9 Messages, in case you like to use more you need to modify the "/jmeter/test.properties" file value "tnum".

~~~~pwsh
PS C:\Users\chpinoto\workspace\az-aks-sb-java\sbapp> jmeter -n -t jmeter/test.jmx -p jmeter/test.properties
Created the tree successfully using jmeter/test.jmx
Starting standalone test @ Thu Mar 25 12:50:13 CET 2021 (1616673013288)
Waiting for possible Shutdown/StopTestNow/HeapDump/ThreadDump message on port 4445
Tidying up ...    @ Thu Mar 25 12:50:14 CET 2021 (1616673014832)
... end of run
PS C:\Users\chpinoto\workspace\az-aks-sb-java\sbapp>
~~~~

Test Name of our Test run is part of the corresponding log file name. In our case we will need to remember "test-202103250848CET":

~~~~pwsh
PS C:\Users\chpinoto\workspace\az-aks-sb-java\sbapp> dir .\jmeter\logs\

    Directory: C:\Users\chpinoto\workspace\az-aks-sb-java\sbapp\jmeter\logs

Mode                 LastWriteTime         Length Name
----                 -------------         ------ ----
-a---           3/25/2021    12:50           3713 test-202103251250CET.tree.result.xml
~~~~

## Retrieve Container Logs via Azure Container Insights

add the test name into the KUSTO query "test-202103250848CET":

~~~~KUSTO
ContainerLog
| where TimeGenerated >= ago(30m)
| where parse_json(LogEntry).deviceName == "myPod"
| project LogEntry
| where parse_json(LogEntry).fileName startswith "test-202103251250CET"
| project duraM = tolong(parse_json(LogEntry).durationMessage)
| summarize count() by length=bin(duraM, 10000.0)
~~~~

![Reporting Table](/sbapp/images/az.portal.aks.logs.containerlog.01.png "Reporting Table")

Histogram:

![Reporting Histogram](/sbapp/images/az.portal.aks.logs.containerlog.02.png "Reporting Histogram")

## Review Console output

### hpa

At peak time we run 6 pods (REPLICAS)

~~~~pwsh
PS C:\Users\chpinoto> kubectl -n mloverdrive get hpa -w
NAME                                REFERENCE                       TARGETS     MINPODS   MAXPODS   REPLICAS   AGE
keda-hpa-mloverdrive-scaledobject   Deployment/servicebusreceiver   0/1 (avg)   3         100       3          12h
keda-hpa-mloverdrive-scaledobject   Deployment/servicebusreceiver   2/1 (avg)   3         100       3          12h
keda-hpa-mloverdrive-scaledobject   Deployment/servicebusreceiver   500m/1 (avg)   3         100       6          12h
keda-hpa-mloverdrive-scaledobject   Deployment/servicebusreceiver   0/1 (avg)      3         100       6          12h
keda-hpa-mloverdrive-scaledobject   Deployment/servicebusreceiver   0/1 (avg)      3         100       6          12h
~~~~

### scaledobject

Keda scaledobject did went from ACTIVE False to ACTIVE True to start scaleout
~~~~pwsh
S C:\Users\chpinoto> kubectl -n mloverdrive get scaledobject -w
NAME                       SCALETARGETKIND      SCALETARGETNAME      MIN   MAX   TRIGGERS           AUTHENTICATION          READY   ACTIVE   AGE
mloverdrive-scaledobject   apps/v1.Deployment   servicebusreceiver   3     100   azure-servicebus   azure-servicebus-auth   True    False    12h
mloverdrive-scaledobject   apps/v1.Deployment   servicebusreceiver   3     100   azure-servicebus   azure-servicebus-auth   True    False    12h
mloverdrive-scaledobject   apps/v1.Deployment   servicebusreceiver   3     100   azure-servicebus   azure-servicebus-auth   True    True     12h
mloverdrive-scaledobject   apps/v1.Deployment   servicebusreceiver   3     100   azure-servicebus   azure-servicebus-auth   True    True     12h
mloverdrive-scaledobject   apps/v1.Deployment   servicebusreceiver   3     100   azure-servicebus   azure-servicebus-auth   True    True     12h
mloverdrive-scaledobject   apps/v1.Deployment   servicebusreceiver   3     100   azure-servicebus   azure-servicebus-auth   True    True     12h
mloverdrive-scaledobject   apps/v1.Deployment   servicebusreceiver   3     100   azure-servicebus   azure-servicebus-auth   True    True     12h
mloverdrive-scaledobject   apps/v1.Deployment   servicebusreceiver   3     100   azure-servicebus   azure-servicebus-auth   True    True     12h
mloverdrive-scaledobject   apps/v1.Deployment   servicebusreceiver   3     100   azure-servicebus   azure-servicebus-auth   True    True     12h
mloverdrive-scaledobject   apps/v1.Deployment   servicebusreceiver   3     100   azure-servicebus   azure-servicebus-auth   True    True     12h
mloverdrive-scaledobject   apps/v1.Deployment   servicebusreceiver   3     100   azure-servicebus   azure-servicebus-auth   True    True     12h
mloverdrive-scaledobject   apps/v1.Deployment   servicebusreceiver   3     100   azure-servicebus   azure-servicebus-auth   True    True     12h
mloverdrive-scaledobject   apps/v1.Deployment   servicebusreceiver   3     100   azure-servicebus   azure-servicebus-auth   True    True     12h
mloverdrive-scaledobject   apps/v1.Deployment   servicebusreceiver   3     100   azure-servicebus   azure-servicebus-auth   True    True     12h
mloverdrive-scaledobject   apps/v1.Deployment   servicebusreceiver   3     100   azure-servicebus   azure-servicebus-auth   True    True     12h
mloverdrive-scaledobject   apps/v1.Deployment   servicebusreceiver   3     100   azure-servicebus   azure-servicebus-auth   True    True     12h
mloverdrive-scaledobject   apps/v1.Deployment   servicebusreceiver   3     100   azure-servicebus   azure-servicebus-auth   True    True     12h
mloverdrive-scaledobject   apps/v1.Deployment   servicebusreceiver   3     100   azure-servicebus   azure-servicebus-auth   True    True     12h
mloverdrive-scaledobject   apps/v1.Deployment   servicebusreceiver   3     100   azure-servicebus   azure-servicebus-auth   True    True     12h
mloverdrive-scaledobject   apps/v1.Deployment   servicebusreceiver   3     100   azure-servicebus   azure-servicebus-auth   True    True     12h
mloverdrive-scaledobject   apps/v1.Deployment   servicebusreceiver   3     100   azure-servicebus   azure-servicebus-auth   True    True     12h
mloverdrive-scaledobject   apps/v1.Deployment   servicebusreceiver   3     100   azure-servicebus   azure-servicebus-auth   True    True     12h
mloverdrive-scaledobject   apps/v1.Deployment   servicebusreceiver   3     100   azure-servicebus   azure-servicebus-auth   True    True     12h
mloverdrive-scaledobject   apps/v1.Deployment   servicebusreceiver   3     100   azure-servicebus   azure-servicebus-auth   True    True     12h
mloverdrive-scaledobject   apps/v1.Deployment   servicebusreceiver   3     100   azure-servicebus   azure-servicebus-auth   True    True     12h
mloverdrive-scaledobject   apps/v1.Deployment   servicebusreceiver   3     100   azure-servicebus   azure-servicebus-auth   True    True     12h
mloverdrive-scaledobject   apps/v1.Deployment   servicebusreceiver   3     100   azure-servicebus   azure-servicebus-auth   True    True     12h
mloverdrive-scaledobject   apps/v1.Deployment   servicebusreceiver   3     100   azure-servicebus   azure-servicebus-auth   True    False    12h
~~~~

### nodes

Number of aks-worker nodes did increase to max of 6

~~~~pwsh
PS C:\Users\chpinoto> kubectl -n mloverdrive get node -w
NAME                             STATUS   ROLES   AGE   VERSION
aks-keda-30372806-vmss000000     Ready    agent   13h   v1.19.7
aks-keda-30372806-vmss000001     Ready    agent   13h   v1.19.7
aks-keda-30372806-vmss000002     Ready    agent   13h   v1.19.7
aks-system-30372806-vmss000000   Ready    agent   13h   v1.19.7
aks-system-30372806-vmss000001   Ready    agent   13h   v1.19.7
aks-system-30372806-vmss000002   Ready    agent   13h   v1.19.7
aks-worker-30372806-vmss000000   Ready    agent   13h   v1.19.7
aks-worker-30372806-vmss000004   Ready    agent   12h   v1.19.7
aks-worker-30372806-vmss000005   Ready    agent   12h   v1.19.7
aks-keda-30372806-vmss000002     Ready    agent   14h   v1.19.7
aks-system-30372806-vmss000000   Ready    agent   14h   v1.19.7
aks-system-30372806-vmss000001   Ready    agent   14h   v1.19.7
aks-system-30372806-vmss000002   Ready    agent   14h   v1.19.7
aks-worker-30372806-vmss000004   Ready    agent   12h   v1.19.7
aks-keda-30372806-vmss000000     Ready    agent   14h   v1.19.7
aks-worker-30372806-vmss000005   Ready    agent   12h   v1.19.7
aks-keda-30372806-vmss000001     Ready    agent   14h   v1.19.7
aks-worker-30372806-vmss000000   Ready    agent   13h   v1.19.7
aks-keda-30372806-vmss000002     Ready    agent   14h   v1.19.7
aks-system-30372806-vmss000000   Ready    agent   14h   v1.19.7
aks-system-30372806-vmss000001   Ready    agent   14h   v1.19.7
aks-system-30372806-vmss000002   Ready    agent   14h   v1.19.7
aks-worker-30372806-vmss000004   Ready    agent   12h   v1.19.7
aks-keda-30372806-vmss000000     Ready    agent   14h   v1.19.7
aks-worker-30372806-vmss000005   Ready    agent   12h   v1.19.7
aks-keda-30372806-vmss000001     Ready    agent   14h   v1.19.7
aks-worker-30372806-vmss000000   Ready    agent   14h   v1.19.7
~~~~

## Open Points:
- Consider the KEDA [ScaledJob](https://github.com/kedacore/keda-docs/blob/master/content/docs/2.0/concepts/scaling-jobs.md) instead of ScaledObject
- Use [AAD Pod Identity](https://github.com/Azure/aad-pod-identity) to access Azure Service Bus via [KEDA](https://keda.sh/docs/2.2/concepts/authentication/#pod-authentication-providers)