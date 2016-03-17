/*
 * Copyright 2016 Google, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License")
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.netflix.spinnaker.clouddriver.kubernetes.deploy.ops.securitygroup

import com.netflix.spinnaker.clouddriver.data.task.Task
import com.netflix.spinnaker.clouddriver.data.task.TaskRepository
import com.netflix.spinnaker.clouddriver.kubernetes.deploy.KubernetesUtil
import com.netflix.spinnaker.clouddriver.kubernetes.deploy.description.securitygroup.KubernetesHttpIngressPath
import com.netflix.spinnaker.clouddriver.kubernetes.deploy.description.securitygroup.KubernetesIngressRule
import com.netflix.spinnaker.clouddriver.kubernetes.deploy.description.securitygroup.UpsertKubernetesSecurityGroupDescription
import com.netflix.spinnaker.clouddriver.orchestration.AtomicOperation
import io.fabric8.kubernetes.api.model.extensions.HTTPIngressPathBuilder
import io.fabric8.kubernetes.api.model.extensions.IngressBuilder
import io.fabric8.kubernetes.api.model.extensions.IngressRuleBuilder

class UpsertKubernetesSecurityGroupAtomicOperation implements AtomicOperation<Void> {
  private static final String BASE_PHASE = "UPSERT_SECURITY_GROUP"

  UpsertKubernetesSecurityGroupAtomicOperation(UpsertKubernetesSecurityGroupDescription description) {
    this.description = description
  }

  private static Task getTask() {
    TaskRepository.threadLocalTask.get()
  }

  UpsertKubernetesSecurityGroupDescription description

  /*
   * curl -X POST -H "Content-Type: application/json" -d '[ { "upsertSecurityGroup": { "securityGroupName": "kub-sg", "namespace": "default", "credentials": "my-kubernetes-account", "ingress": { "serviceName": "kub-nginx", "port": 80 } } } ]' localhost:7002/kubernetes/ops
   */
  @Override
  Void operate(List priorOutputs) {
    task.updateStatus BASE_PHASE, "Initializing upsert of ingress."
    task.updateStatus BASE_PHASE, "Looking up provided namespace..."

    def credentials = description.credentials
    def namespace = KubernetesUtil.validateNamespace(credentials, description.namespace)

    task.updateStatus BASE_PHASE, "Looking up old ingress..."

    def oldIngress = credentials.apiAdaptor.getIngress(namespace, description.securityGroupName)

    def ingress = new IngressBuilder().withNewMetadata().withName(description.securityGroupName).withNamespace(namespace).endMetadata()

    task.updateStatus BASE_PHASE, "Attaching requested service..."
    ingress = ingress.withNewSpec().withNewBackend().withServiceName(description.ingress.serviceName).withNewServicePort(description.ingress.port).endBackend()

    task.updateStatus BASE_PHASE, "Setting requested rules..."

    def rules = description.rules?.collect { KubernetesIngressRule rule ->
      def res = new IngressRuleBuilder().withHost(rule.host).withNewHttp()

      def paths = rule.value?.http?.paths?.collect { KubernetesHttpIngressPath path ->
        return new HTTPIngressPathBuilder().withPath(path.path)
            .withNewBackend()
            .withServiceName(path.ingress?.serviceName)
            .withNewServicePort(path.ingress?.port)
            .endBackend()
            .build()
      }

      res = res.withPaths(paths)

      return res.endHttp().build()
    }

    ingress = ingress.withRules(rules)

    ingress = ingress.endSpec().build()

    oldIngress ? credentials.apiAdaptor.replaceIngress(namespace, description.securityGroupName, ingress) :
        credentials.apiAdaptor.createIngress(namespace, ingress)

    null
  }
}
