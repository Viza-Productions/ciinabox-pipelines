
/************************************
ecsTask (
  action: 'runAndWait',
  taskDefinition: 'example-task-definition',
  cluster: 'example-cluster',
  region: 'us-east-1',
  accountId: '12345678',
  role: 'ciinabox',
)
************************************/

@Grab(group='com.amazonaws', module='aws-java-sdk-ecs', version='1.11.359')
@Grab(group='com.amazonaws', module='aws-java-sdk-iam', version='1.11.359')
@Grab(group='com.amazonaws', module='aws-java-sdk-sts', version='1.11.359')

import com.amazonaws.auth.AWSStaticCredentialsProvider
import com.amazonaws.auth.BasicSessionCredentials
import com.amazonaws.services.ecs.*
import com.amazonaws.services.ecs.model.NetworkConfiguration
import com.amazonaws.services.ecs.model.AwsVpcConfiguration
import com.amazonaws.services.ecs.model.DescribeTasksRequest
import com.amazonaws.services.ecs.model.RunTaskRequest
import com.amazonaws.services.securitytoken.AWSSecurityTokenServiceClient
import com.amazonaws.services.securitytoken.model.AssumeRoleRequest
import com.amazonaws.waiters.NoOpWaiterHandler
import com.amazonaws.waiters.WaiterParameters

import java.util.concurrent.*

def call(body) {
  def config = body
  def client = setupECSClient(config.region, config.accountId, config.role)

  config.wait = config.get('wait', false)

  handleActionRequest(client, config)
}

def handleActionRequest(client, config) {
  def success = true

  switch (config.action) {
    case 'runAndWait':
      def startedTasks = startTask(client, config)
      success = wait(client, config, startedTasks)
      break
    default:
      throw new GroovyRuntimeException("The specified action '${config.action}' is not implemented.")
  }

  if(!success) {
    throw new Exception("Task ${config.taskDefinition} failed to run.")
  }
}

@NonCPS
def startTask(client, config) {
  def taskRequest = new RunTaskRequest()
  taskRequest.withCluster(config.cluster)
  taskRequest.launchType = config.launchType ? config.launchType : "EC2"
  taskRequest.taskDefinition = config.taskDefinition

  if (taskRequest.launchType == 'FARGATE') {
    def awsVpcConfiguration = new AwsVpcConfiguration().withSubnets(config.subnets).withSecurityGroups(config.securityGroup)
    def networkConfiguration = new NetworkConfiguration().withAwsvpcConfiguration(awsVpcConfiguration)
    taskRequest.withNetworkConfiguration(networkConfiguration)
  }


  println "Starting task ${config.taskDefinition} in cluster ${config.cluster}"
  def runResult = client.runTask(taskRequest)
  println "Started task ${runResult.tasks.first().taskArn}"
  return runResult
}

@NonCPS
def wait(client, config, startedTasks) {
  def waiter = client.waiters().tasksStopped()

  def describeTasksRequest = new DescribeTasksRequest()
  describeTasksRequest.withCluster(config.cluster)
  describeTasksRequest.withTasks(startedTasks.tasks.collectMany { [it.taskArn] })

  Thread.sleep(5 * 1000)  // Allow the tasks to start
  try {
    Future future = waiter.runAsync(
      new WaiterParameters<>(describeTasksRequest),
      new NoOpWaiterHandler()
    )
    while(!future.isDone()) {
      try {
        println "waiting for task to complete"
        Thread.sleep(5 * 1000)
      } catch(InterruptedException ex) {
        println "We seem to be timing out ${ex}...ignoring"
      }
    }

    def taskDescriptions = client.describeTasks(describeTasksRequest)
    if (taskDescriptions.tasks.size() != 1) {
      println "Couldn't find launched task"
      return false
    }
    for (task in taskDescriptions.tasks) {
      for (container in task.containers) {
        if (container.exitCode != 0) {
          println "Non zero exit code in container: ${container} of task ${task}"
          return false
        }
      }
    }
    return true
  } catch(Exception e) {
      println "Waiting for task failed. - ${e}"
      return false
  }
}

@NonCPS
def setupECSClient(region, awsAccountId = null, role = null) {
  def cb = AmazonECSClientBuilder.standard().withRegion(region)
  def creds = getCredentials(awsAccountId, region, role)
  if(creds != null) {
    cb.withCredentials(new AWSStaticCredentialsProvider(creds))
  }
  return cb.build()
}

@NonCPS
def getCredentials(awsAccountId, region, roleName) {
  def env = System.getenv()
  if(env['AWS_SESSION_TOKEN'] != null) {
    return new BasicSessionCredentials(
      env['AWS_ACCESS_KEY_ID'],
      env['AWS_SECRET_ACCESS_KEY'],
      env['AWS_SESSION_TOKEN']
    )
  } else if(awsAccountId != null && roleName != null) {
    def stsCreds = assumeRole(awsAccountId, region, roleName)
    return new BasicSessionCredentials(
      stsCreds.getAccessKeyId(),
      stsCreds.getSecretAccessKey(),
      stsCreds.getSessionToken()
    )
  } else {
    return null
  }
}

@NonCPS
def assumeRole(awsAccountId, region, roleName) {
  def roleArn = "arn:aws:iam::" + awsAccountId + ":role/" + roleName
  def roleSessionName = "sts-session-" + awsAccountId
  println "assuming IAM role ${roleArn}"
  def sts = new AWSSecurityTokenServiceClient()
  if (!region.equals("us-east-1")) {
      sts.setEndpoint("sts." + region + ".amazonaws.com")
  }
  def assumeRoleResult = sts.assumeRole(new AssumeRoleRequest()
            .withRoleArn(roleArn).withDurationSeconds(3600)
            .withRoleSessionName(roleSessionName))
  return assumeRoleResult.getCredentials()
}