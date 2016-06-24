/* Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.camunda.bpm.engine.impl.persistence.deploy;

import static org.camunda.bpm.engine.impl.util.EnsureUtil.ensureNotNull;

import java.io.InputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

import org.camunda.bpm.engine.ProcessEngineException;
import org.camunda.bpm.engine.exception.cmmn.CaseDefinitionNotFoundException;
import org.camunda.bpm.engine.exception.dmn.DecisionDefinitionNotFoundException;
import org.camunda.bpm.engine.impl.ProcessDefinitionQueryImpl;
import org.camunda.bpm.engine.impl.ProcessEngineLogger;
import org.camunda.bpm.engine.impl.cmd.GetDeploymentResourceCmd;
import org.camunda.bpm.engine.impl.cmmn.entity.repository.CaseDefinitionEntity;
import org.camunda.bpm.engine.impl.cmmn.entity.repository.CaseDefinitionQueryImpl;
import org.camunda.bpm.engine.impl.context.Context;
import org.camunda.bpm.engine.impl.db.EnginePersistenceLogger;
import org.camunda.bpm.engine.impl.dmn.entity.repository.DecisionDefinitionEntity;
import org.camunda.bpm.engine.impl.dmn.entity.repository.DecisionDefinitionQueryImpl;
import org.camunda.bpm.engine.impl.dmn.entity.repository.DecisionRequirementDefinitionEntity;
import org.camunda.bpm.engine.impl.dmn.entity.repository.DecisionRequirementDefinitionQueryImpl;
import org.camunda.bpm.engine.impl.interceptor.CommandContext;
import org.camunda.bpm.engine.impl.persistence.entity.DeploymentEntity;
import org.camunda.bpm.engine.impl.persistence.entity.ProcessDefinitionEntity;
import org.camunda.bpm.engine.repository.CaseDefinition;
import org.camunda.bpm.engine.repository.DecisionDefinition;
import org.camunda.bpm.engine.repository.DecisionRequirementDefinition;
import org.camunda.bpm.engine.repository.ProcessDefinition;
import org.camunda.bpm.model.bpmn.Bpmn;
import org.camunda.bpm.model.bpmn.BpmnModelInstance;
import org.camunda.bpm.model.cmmn.Cmmn;
import org.camunda.bpm.model.cmmn.CmmnModelInstance;
import org.camunda.bpm.model.dmn.Dmn;
import org.camunda.bpm.model.dmn.DmnModelInstance;


/**
 * @author Tom Baeyens
 * @author Falko Menge
 */
public class DeploymentCache {

  protected static final EnginePersistenceLogger LOG = ProcessEngineLogger.PERSISTENCE_LOGGER;

  protected Map<String, ProcessDefinitionEntity> processDefinitionCache = new HashMap<String, ProcessDefinitionEntity>();
  protected Map<String, CaseDefinitionEntity> caseDefinitionCache = new HashMap<String, CaseDefinitionEntity>();
  protected Map<String, DecisionDefinitionEntity> decisionDefinitionCache = new HashMap<String, DecisionDefinitionEntity>();
  protected Map<String, DecisionRequirementDefinitionEntity> decisionRequirementDefinitionCache = new HashMap<String, DecisionRequirementDefinitionEntity>();

  protected Map<String, BpmnModelInstance> bpmnModelInstanceCache = new HashMap<String, BpmnModelInstance>();
  protected Map<String, CmmnModelInstance> cmmnModelInstanceCache = new HashMap<String, CmmnModelInstance>();
  protected Map<String, DmnModelInstance> dmnModelInstanceCache = new HashMap<String, DmnModelInstance>();
  protected List<Deployer> deployers;

  public void deploy(final DeploymentEntity deployment) {
    Context.getCommandContext().runWithoutAuthorization(new Callable<Void>() {
      public Void call() throws Exception {
        for (Deployer deployer: deployers) {
          deployer.deploy(deployment);
        }
        return null;
      }
    });
  }

  // PROCESS DEFINITION ////////////////////////////////////////////////////////////////////////////////

  public ProcessDefinitionEntity findProcessDefinitionFromCache(String processDefinitionId) {
    return processDefinitionCache.get(processDefinitionId);
  }

  public ProcessDefinitionEntity findDeployedProcessDefinitionById(String processDefinitionId) {
    ensureNotNull("Invalid process definition id", "processDefinitionId", processDefinitionId);
    CommandContext commandContext = Context.getCommandContext();
    ProcessDefinitionEntity processDefinition = commandContext.getDbEntityManager().getCachedEntity(ProcessDefinitionEntity.class, processDefinitionId);
    if (processDefinition == null) {
      processDefinition = commandContext
        .getProcessDefinitionManager()
        .findLatestProcessDefinitionById(processDefinitionId);
    }

    ensureNotNull("no deployed process definition found with id '" + processDefinitionId + "'", "processDefinition", processDefinition);
    processDefinition = resolveProcessDefinition(processDefinition);
    return processDefinition;
  }

  /**
   * @return the latest version of the process definition with the given key (from any tenant)
   *
   * @throws ProcessEngineException if more than one tenant has a process definition with the given key
   *
   * @see #findDeployedLatestProcessDefinitionByKeyAndTenantId(String, String)
   */
  public ProcessDefinitionEntity findDeployedLatestProcessDefinitionByKey(String processDefinitionKey) {
    ProcessDefinitionEntity processDefinition = Context
      .getCommandContext()
      .getProcessDefinitionManager()
      .findLatestProcessDefinitionByKey(processDefinitionKey);
    ensureNotNull("no processes deployed with key '" + processDefinitionKey + "'", "processDefinition", processDefinition);
    processDefinition = resolveProcessDefinition(processDefinition);
    return processDefinition;
  }

  /**
   * @return the latest version of the process definition with the given key and tenant id
   */
  public ProcessDefinitionEntity findDeployedLatestProcessDefinitionByKeyAndTenantId(String processDefinitionKey, String tenantId) {
    ProcessDefinitionEntity processDefinition = Context
        .getCommandContext()
        .getProcessDefinitionManager()
        .findLatestProcessDefinitionByKeyAndTenantId(processDefinitionKey, tenantId);
    ensureNotNull("no processes deployed with key '" + processDefinitionKey + "' and tenant-id '" + tenantId + "'", "processDefinition", processDefinition);
    processDefinition = resolveProcessDefinition(processDefinition);
    return processDefinition;
  }

  public ProcessDefinitionEntity findDeployedProcessDefinitionByKeyVersionAndTenantId(final String processDefinitionKey, final Integer processDefinitionVersion, final String tenantId) {
    final CommandContext commandContext = Context.getCommandContext();
    ProcessDefinitionEntity processDefinition = commandContext.runWithoutAuthorization(new Callable<ProcessDefinitionEntity>() {
      public ProcessDefinitionEntity call() throws Exception {
        return (ProcessDefinitionEntity) commandContext
          .getProcessDefinitionManager()
          .findProcessDefinitionByKeyVersionAndTenantId(processDefinitionKey, processDefinitionVersion, tenantId);
      }
    });
    ensureNotNull("no processes deployed with key = '" + processDefinitionKey + "', version = '" + processDefinitionVersion
        + "' and tenant-id = '" + tenantId + "'", "processDefinition", processDefinition);
    processDefinition = resolveProcessDefinition(processDefinition);
    return processDefinition;
  }

  public ProcessDefinitionEntity findDeployedProcessDefinitionByDeploymentAndKey(String deploymentId, String processDefinitionKey) {
    ProcessDefinitionEntity processDefinition = Context
      .getCommandContext()
      .getProcessDefinitionManager()
      .findProcessDefinitionByDeploymentAndKey(deploymentId, processDefinitionKey);
    ensureNotNull("no processes deployed with key = '" + processDefinitionKey + "' in deployment = '" + deploymentId + "'", "processDefinition", processDefinition);
    processDefinition = resolveProcessDefinition(processDefinition);
    return processDefinition;
  }

  public ProcessDefinitionEntity resolveProcessDefinition(ProcessDefinitionEntity processDefinition) {
    String processDefinitionId = processDefinition.getId();
    String deploymentId = processDefinition.getDeploymentId();
    ProcessDefinitionEntity cachedProcessDefinition = processDefinitionCache.get(processDefinitionId);
    if (cachedProcessDefinition==null) {
      DeploymentEntity deployment = Context
        .getCommandContext()
        .getDeploymentManager()
        .findDeploymentById(deploymentId);
      deployment.setNew(false);
      deploy(deployment);
      cachedProcessDefinition = processDefinitionCache.get(processDefinitionId);

      ensureNotNull("deployment '" + deploymentId + "' didn't put process definition '" + processDefinitionId + "' in the cache", "cachedProcessDefinition", cachedProcessDefinition);
    } else {
      // update cached process definition
      cachedProcessDefinition.updateModifiedFieldsFromEntity(processDefinition);
    }
    return cachedProcessDefinition;
  }

  public BpmnModelInstance findBpmnModelInstanceForProcessDefinition(ProcessDefinitionEntity processDefinitionEntity) {
    BpmnModelInstance bpmnModelInstance = bpmnModelInstanceCache.get(processDefinitionEntity.getId());
    if(bpmnModelInstance == null) {
      bpmnModelInstance = loadAndCacheBpmnModelInstance(processDefinitionEntity);
    }
    return bpmnModelInstance;
  }

  public BpmnModelInstance findBpmnModelInstanceForProcessDefinition(String processDefinitionId) {
    BpmnModelInstance bpmnModelInstance = bpmnModelInstanceCache.get(processDefinitionId);
    if(bpmnModelInstance == null) {
      ProcessDefinitionEntity processDefinition = findDeployedProcessDefinitionById(processDefinitionId);
      bpmnModelInstance = loadAndCacheBpmnModelInstance(processDefinition);
    }
    return bpmnModelInstance;
  }

  protected BpmnModelInstance loadAndCacheBpmnModelInstance(final ProcessDefinitionEntity processDefinitionEntity) {
    final CommandContext commandContext = Context.getCommandContext();
    InputStream bpmnResourceInputStream = commandContext.runWithoutAuthorization(new Callable<InputStream>() {
      public InputStream call() throws Exception {
        return new GetDeploymentResourceCmd(processDefinitionEntity.getDeploymentId(), processDefinitionEntity.getResourceName()).execute(commandContext);
      }
    });

    try {
      BpmnModelInstance bpmnModelInstance = Bpmn.readModelFromStream(bpmnResourceInputStream);
      bpmnModelInstanceCache.put(processDefinitionEntity.getId(), bpmnModelInstance);
      return bpmnModelInstance;
    }catch(Exception e) {
      throw LOG.loadModelException("BPMN", "process", processDefinitionEntity.getId(), e);
    }
  }

  public void addProcessDefinition(ProcessDefinitionEntity processDefinition) {
    processDefinitionCache.put(processDefinition.getId(), processDefinition);
  }

  public void removeProcessDefinition(String processDefinitionId) {
    processDefinitionCache.remove(processDefinitionId);
    bpmnModelInstanceCache.remove(processDefinitionId);
  }

  public void discardProcessDefinitionCache() {
    processDefinitionCache.clear();
    bpmnModelInstanceCache.clear();
  }

  // CASE DEFINITION ////////////////////////////////////////////////////////////////////////////////

  public CaseDefinitionEntity findCaseDefinitionFromCache(String caseDefinitionId) {
    return caseDefinitionCache.get(caseDefinitionId);
  }

  public CaseDefinitionEntity findDeployedCaseDefinitionById(String caseDefinitionId) {
    ensureNotNull("Invalid case definition id", "caseDefinitionId", caseDefinitionId);

    CommandContext commandContext = Context.getCommandContext();

    // try to load case definition from cache
    CaseDefinitionEntity caseDefinition = commandContext
      .getDbEntityManager()
      .getCachedEntity(CaseDefinitionEntity.class, caseDefinitionId);

    if (caseDefinition == null) {

      // if not found, then load the case definition
      // from db
      caseDefinition = commandContext
        .getCaseDefinitionManager()
        .findCaseDefinitionById(caseDefinitionId);

    }

    ensureNotNull(CaseDefinitionNotFoundException.class, "no deployed case definition found with id '" + caseDefinitionId + "'", "caseDefinition", caseDefinition);

    caseDefinition = resolveCaseDefinition(caseDefinition);

    return caseDefinition;
  }

  /**
   * @return the latest version of the case definition with the given key (from any tenant)
   *
   * @throws ProcessEngineException if more than one tenant has a case definition with the given key
   *
   * @see #findDeployedLatestCaseDefinitionByKeyAndTenantId(String, String)
   */
  public CaseDefinitionEntity findDeployedLatestCaseDefinitionByKey(String caseDefinitionKey) {
    ensureNotNull("Invalid case definition key", "caseDefinitionKey", caseDefinitionKey);

    // load case definition by key from db
    CaseDefinitionEntity caseDefinition = Context
      .getCommandContext()
      .getCaseDefinitionManager()
      .findLatestCaseDefinitionByKey(caseDefinitionKey);

    ensureNotNull(CaseDefinitionNotFoundException.class, "no case definition deployed with key '" + caseDefinitionKey + "'", "caseDefinition", caseDefinition);

    caseDefinition = resolveCaseDefinition(caseDefinition);

    return caseDefinition;
  }

  /**
   * @return the latest version of the case definition with the given key and tenant id
   */
  public CaseDefinitionEntity findDeployedLatestCaseDefinitionByKeyAndTenantId(String caseDefinitionKey, String tenantId) {
    ensureNotNull("Invalid case definition key", "caseDefinitionKey", caseDefinitionKey);

    // load case definition by key from db
    CaseDefinitionEntity caseDefinition = Context
      .getCommandContext()
      .getCaseDefinitionManager()
      .findLatestCaseDefinitionByKeyAndTenantId(caseDefinitionKey, tenantId);

    ensureNotNull(CaseDefinitionNotFoundException.class, "no case definition deployed with key '" + caseDefinitionKey + "' and tenant-id '" + tenantId + "'", "caseDefinition", caseDefinition);

    caseDefinition = resolveCaseDefinition(caseDefinition);

    return caseDefinition;
  }

  public CaseDefinitionEntity findDeployedCaseDefinitionByKeyVersionAndTenantId(String caseDefinitionKey, Integer caseDefinitionVersion, String tenantId) {

    CaseDefinitionEntity caseDefinition = Context
      .getCommandContext()
      .getCaseDefinitionManager()
      .findCaseDefinitionByKeyVersionAndTenantId(caseDefinitionKey, caseDefinitionVersion, tenantId);

    ensureNotNull(CaseDefinitionNotFoundException.class, "no case definition deployed with key = '" + caseDefinitionKey + "', version = '" + caseDefinitionVersion + "'"
        + " and tenant-id = '" + tenantId + "'", "caseDefinition", caseDefinition);
    caseDefinition = resolveCaseDefinition(caseDefinition);

    return caseDefinition;
  }

  public CaseDefinitionEntity findDeployedCaseDefinitionByDeploymentAndKey(String deploymentId, String caseDefinitionKey) {
    CaseDefinitionEntity caseDefinition = Context
      .getCommandContext()
      .getCaseDefinitionManager()
      .findCaseDefinitionByDeploymentAndKey(deploymentId, caseDefinitionKey);

    ensureNotNull(CaseDefinitionNotFoundException.class, "no case definition deployed with key = '" + caseDefinitionKey + "' in deployment = '" + deploymentId + "'", "caseDefinition", caseDefinition);
    caseDefinition = resolveCaseDefinition(caseDefinition);

    return caseDefinition;
  }

  public CaseDefinitionEntity getCaseDefinitionById(String caseDefinitionId) {
    ensureNotNull("caseDefinitionId", caseDefinitionId);

    CaseDefinitionEntity caseDefinition = caseDefinitionCache.get(caseDefinitionId);

    if (caseDefinition == null) {
      caseDefinition = findDeployedCaseDefinitionById(caseDefinitionId);

    }

    return caseDefinition;
  }

  public CaseDefinitionEntity resolveCaseDefinition(CaseDefinitionEntity caseDefinition) {
    String caseDefinitionId = caseDefinition.getId();
    String deploymentId = caseDefinition.getDeploymentId();

    CaseDefinitionEntity cachedCaseDefinition = caseDefinitionCache.get(caseDefinitionId);

    if (cachedCaseDefinition==null) {
      DeploymentEntity deployment = Context
        .getCommandContext()
        .getDeploymentManager()
        .findDeploymentById(deploymentId);

      deployment.setNew(false);
      deploy(deployment);

      cachedCaseDefinition = caseDefinitionCache.get(caseDefinitionId);

      ensureNotNull("deployment '" + deploymentId + "' didn't put case definition '" + caseDefinitionId + "' in the cache", "cachedCaseDefinition", cachedCaseDefinition);

    }
    return cachedCaseDefinition;
  }

  public CmmnModelInstance findCmmnModelInstanceForCaseDefinition(String caseDefinitionId) {
    CmmnModelInstance cmmnModelInstance = cmmnModelInstanceCache.get(caseDefinitionId);
    if(cmmnModelInstance == null) {

      CaseDefinitionEntity caseDefinition = findDeployedCaseDefinitionById(caseDefinitionId);
      final String deploymentId = caseDefinition.getDeploymentId();
      final String resourceName = caseDefinition.getResourceName();

      final CommandContext commandContext = Context.getCommandContext();
      InputStream cmmnResourceInputStream = commandContext.runWithoutAuthorization(new Callable<InputStream>() {
        public InputStream call() throws Exception {
          return new GetDeploymentResourceCmd(deploymentId, resourceName).execute(commandContext);
        }
      });

      try {
        cmmnModelInstance = Cmmn.readModelFromStream(cmmnResourceInputStream);
      }catch(Exception e) {
        throw LOG.loadModelException("CMMN", "case", caseDefinitionId, e);
      }

      // put model instance into cache.
      cmmnModelInstanceCache.put(caseDefinitionId, cmmnModelInstance);

    }
    return cmmnModelInstance;
  }

  public void addCaseDefinition(CaseDefinitionEntity caseDefinition) {
    caseDefinitionCache.put(caseDefinition.getId(), caseDefinition);
  }

  public void removeCaseDefinition(String caseDefinitionId) {
    caseDefinitionCache.remove(caseDefinitionId);
    cmmnModelInstanceCache.remove(caseDefinitionId);
  }

  public void discardCaseDefinitionCache() {
    caseDefinitionCache.clear();
    cmmnModelInstanceCache.clear();
  }

  // DECISION DEFINITION ////////////////////////////////////////////////////////////////////////////

  public DecisionDefinitionEntity findDecisionDefinitionFromCache(String decisionDefinitionId) {
    return decisionDefinitionCache.get(decisionDefinitionId);
  }

  public DecisionDefinitionEntity findDeployedDecisionDefinitionById(String decisionDefinitionId) {
    ensureNotNull("Invalid decision definition id", "decisionDefinitionId", decisionDefinitionId);

    CommandContext commandContext = Context.getCommandContext();

    // try to load decision definition from cache
    DecisionDefinitionEntity decisionDefinition = commandContext
      .getDbEntityManager()
      .getCachedEntity(DecisionDefinitionEntity.class, decisionDefinitionId);

    if (decisionDefinition == null) {

      // if not found, then load the decision definition
      // from db
      decisionDefinition = commandContext
        .getDecisionDefinitionManager()
        .findDecisionDefinitionById(decisionDefinitionId);

    }

    ensureNotNull(DecisionDefinitionNotFoundException.class, "no deployed decision definition found with id '" + decisionDefinitionId + "'", "decisionDefinition", decisionDefinition);

    decisionDefinition = resolveDecisionDefinition(decisionDefinition);

    return decisionDefinition;
  }

  public DecisionDefinition findDeployedLatestDecisionDefinitionByKey(String decisionDefinitionKey) {
    ensureNotNull("Invalid decision definition key", "decisionDefinitionKey", decisionDefinitionKey);

    DecisionDefinitionEntity decisionDefinition = Context
      .getCommandContext()
      .getDecisionDefinitionManager()
      .findLatestDecisionDefinitionByKey(decisionDefinitionKey);

    ensureNotNull(DecisionDefinitionNotFoundException.class, "no decision definition deployed with key '" + decisionDefinitionKey + "'", "decisionDefinition", decisionDefinition);

    decisionDefinition = resolveDecisionDefinition(decisionDefinition);

    return decisionDefinition;
  }

  public DecisionDefinition findDeployedLatestDecisionDefinitionByKeyAndTenantId(String decisionDefinitionKey, String tenantId) {
    ensureNotNull("Invalid decision definition key", "decisionDefinitionKey", decisionDefinitionKey);

    DecisionDefinitionEntity decisionDefinition = Context
      .getCommandContext()
      .getDecisionDefinitionManager()
      .findLatestDecisionDefinitionByKeyAndTenantId(decisionDefinitionKey, tenantId);

    ensureNotNull(DecisionDefinitionNotFoundException.class, "no decision definition deployed with key '" + decisionDefinitionKey + "' and tenant-id '" + tenantId + "'", "decisionDefinition", decisionDefinition);

    decisionDefinition = resolveDecisionDefinition(decisionDefinition);

    return decisionDefinition;
  }

  public DecisionDefinition findDeployedDecisionDefinitionByDeploymentAndKey(String deploymentId, String decisionDefinitionKey) {
    DecisionDefinitionEntity decisionDefinition = Context
      .getCommandContext()
      .getDecisionDefinitionManager()
      .findDecisionDefinitionByDeploymentAndKey(deploymentId, decisionDefinitionKey);

    ensureNotNull(DecisionDefinitionNotFoundException.class, "no decision definition deployed with key = '" + decisionDefinitionKey + "' in deployment = '" + deploymentId + "'", "decisionDefinition", decisionDefinition);
    decisionDefinition = resolveDecisionDefinition(decisionDefinition);

    return decisionDefinition;
  }

  public DecisionDefinition findDeployedDecisionDefinitionByKeyAndVersion(String decisionDefinitionKey, Integer decisionDefinitionVersion) {
    DecisionDefinitionEntity decisionDefinition = Context
      .getCommandContext()
      .getDecisionDefinitionManager()
      .findDecisionDefinitionByKeyAndVersion(decisionDefinitionKey, decisionDefinitionVersion);

    ensureNotNull(DecisionDefinitionNotFoundException.class, "no decision definition deployed with key = '" + decisionDefinitionKey + "' and version = '" + decisionDefinitionVersion + "'", "decisionDefinition", decisionDefinition);
    decisionDefinition = resolveDecisionDefinition(decisionDefinition);

    return decisionDefinition;
  }

  public DecisionDefinition findDeployedDecisionDefinitionByKeyVersionAndTenantId(String decisionDefinitionKey, Integer decisionDefinitionVersion, String tenantId) {
    DecisionDefinitionEntity decisionDefinition = Context
      .getCommandContext()
      .getDecisionDefinitionManager()
      .findDecisionDefinitionByKeyVersionAndTenantId(decisionDefinitionKey, decisionDefinitionVersion, tenantId);

    ensureNotNull(DecisionDefinitionNotFoundException.class, "no decision definition deployed with key = '" + decisionDefinitionKey + "', version = '" + decisionDefinitionVersion + "' and tenant-id '" + tenantId + "'", "decisionDefinition", decisionDefinition);
    decisionDefinition = resolveDecisionDefinition(decisionDefinition);

    return decisionDefinition;
  }

  public DecisionDefinitionEntity resolveDecisionDefinition(DecisionDefinitionEntity decisionDefinition) {
    String decisionDefinitionId = decisionDefinition.getId();
    String deploymentId = decisionDefinition.getDeploymentId();

    DecisionDefinitionEntity cachedDecisionDefinition = decisionDefinitionCache.get(decisionDefinitionId);

    if (cachedDecisionDefinition==null) {
      DeploymentEntity deployment = Context
        .getCommandContext()
        .getDeploymentManager()
        .findDeploymentById(deploymentId);

      deployment.setNew(false);
      deploy(deployment);

      cachedDecisionDefinition = decisionDefinitionCache.get(decisionDefinitionId);

      ensureNotNull("deployment '" + deploymentId + "' didn't put decision definition '" + decisionDefinitionId + "' in the cache", "cachedDecisionDefinition", cachedDecisionDefinition);

    }
    return cachedDecisionDefinition;
  }

  public DmnModelInstance findDmnModelInstanceForDecisionDefinition(String decisionDefinitionId) {
    DmnModelInstance dmnModelInstance = dmnModelInstanceCache.get(decisionDefinitionId);

    if(dmnModelInstance == null) {

      DecisionDefinitionEntity decisionDefinition = findDeployedDecisionDefinitionById(decisionDefinitionId);
      final String deploymentId = decisionDefinition.getDeploymentId();
      final String resourceName = decisionDefinition.getResourceName();

      final CommandContext commandContext = Context.getCommandContext();
      InputStream dmnResourceInputStream = commandContext.runWithoutAuthorization(new Callable<InputStream>() {
        public InputStream call() throws Exception {
          return new GetDeploymentResourceCmd(deploymentId, resourceName).execute(commandContext);
        }
      });

      try {
        dmnModelInstance = Dmn.readModelFromStream(dmnResourceInputStream);
      }catch(Exception e) {
        throw LOG.loadModelException("DMN", "decision", decisionDefinitionId, e);
      }

      // put model instance into cache.
      dmnModelInstanceCache.put(decisionDefinitionId, dmnModelInstance);
    }

    return dmnModelInstance;
  }

  public void addDecisionDefinition(DecisionDefinitionEntity decisionDefinition) {
    decisionDefinitionCache.put(decisionDefinition.getId(), decisionDefinition);
  }

  public void removeDecisionDefinition(String decisionDefinitionId) {
    decisionDefinitionCache.remove(decisionDefinitionId);
    dmnModelInstanceCache.remove(decisionDefinitionId);
  }

  public void discardDecisionDefinitionCache() {
    decisionDefinitionCache.clear();
    dmnModelInstanceCache.clear();
  }

  //DECISION REQUIREMENT DEFINITION ////////////////////////////////////////////////////////////////////////////

  public void addDecisionRequirementDefinition(DecisionRequirementDefinitionEntity decisionRequirementDefinition) {
    decisionRequirementDefinitionCache.put(decisionRequirementDefinition.getId(), decisionRequirementDefinition);
  }

  public DecisionRequirementDefinitionEntity findDecisionRequirementDefinitionFromCache(String decisionRequirementDefinitionId) {
    return decisionRequirementDefinitionCache.get(decisionRequirementDefinitionId);
  }

  public DecisionRequirementDefinitionEntity findDeployedDecisionRequirementDefinitionById(String decisionRequirementDefinitionId) {
    ensureNotNull("Invalid decision requirement definition id", "decisionRequirementDefinitionId", decisionRequirementDefinitionId);

    CommandContext commandContext = Context.getCommandContext();

    // try to load definition from cache
    DecisionRequirementDefinitionEntity decisionRequirementDefinition = commandContext
      .getDbEntityManager()
      .getCachedEntity(DecisionRequirementDefinitionEntity.class, decisionRequirementDefinitionId);

    if (decisionRequirementDefinition == null) {

      // if not found, then load the definition from db
      decisionRequirementDefinition = commandContext
        .getDecisionDefinitionManager()
        .findDecisionRequirementDefinitionById(decisionRequirementDefinitionId);
    }

    ensureNotNull("no deployed decision requirement definition found with id '" + decisionRequirementDefinitionId + "'",
        "decisionRequirementDefinition", decisionRequirementDefinition);

    decisionRequirementDefinition = resolveDecisionRequirementDefinition(decisionRequirementDefinition);

    return decisionRequirementDefinition;
  }

  public DecisionRequirementDefinitionEntity resolveDecisionRequirementDefinition(DecisionRequirementDefinitionEntity decisionRequirementDefinition) {
    String decisionRequirementDefinitionId = decisionRequirementDefinition.getId();
    String deploymentId = decisionRequirementDefinition.getDeploymentId();

    DecisionRequirementDefinitionEntity cachedDecisionRequirementDefinition = decisionRequirementDefinitionCache.get(decisionRequirementDefinitionId);

    if (cachedDecisionRequirementDefinition==null) {
      DeploymentEntity deployment = Context
        .getCommandContext()
        .getDeploymentManager()
        .findDeploymentById(deploymentId);

      deployment.setNew(false);
      deploy(deployment);

      cachedDecisionRequirementDefinition = decisionRequirementDefinitionCache.get(decisionRequirementDefinitionId);

      ensureNotNull("deployment '" + deploymentId + "' didn't put decision requirement definition '" + decisionRequirementDefinitionId + "' in the cache", "cachedDecisionRequirementDefinition", cachedDecisionRequirementDefinition);

    }
    return cachedDecisionRequirementDefinition;
  }

  public void discardDecisionRequirementDefinitionCache() {
    decisionDefinitionCache.clear();
  }

  public void removeDecisionRequirementDefinition(String decisionRequirementDefinitionId) {
    decisionRequirementDefinitionCache.remove(decisionRequirementDefinitionId);
  }

  // getters and setters //////////////////////////////////////////////////////

  public Map<String, BpmnModelInstance> getBpmnModelInstanceCache() {
    return bpmnModelInstanceCache;
  }

  public Map<String, CmmnModelInstance> getCmmnModelInstanceCache() {
    return cmmnModelInstanceCache;
  }

  public Map<String, DmnModelInstance> getDmnModelInstanceCache() {
    return dmnModelInstanceCache;
  }

  public Map<String, ProcessDefinitionEntity> getProcessDefinitionCache() {
    return processDefinitionCache;
  }

  public void setProcessDefinitionCache(Map<String, ProcessDefinitionEntity> processDefinitionCache) {
    this.processDefinitionCache = processDefinitionCache;
  }

  public Map<String, CaseDefinitionEntity> getCaseDefinitionCache() {
    return caseDefinitionCache;
  }

  public void setCaseDefinitionCache(Map<String, CaseDefinitionEntity> caseDefinitionCache) {
    this.caseDefinitionCache = caseDefinitionCache;
  }

  public List<Deployer> getDeployers() {
    return deployers;
  }

  public void setDeployers(List<Deployer> deployers) {
    this.deployers = deployers;
  }

  public void removeDeployment(String deploymentId) {
    removeAllProcessDefinitionsByDeploymentId(deploymentId);
    removeAllCaseDefinitionsByDeploymentId(deploymentId);
    removeAllDecisionDefinitionsByDeploymentId(deploymentId);
    removeAllDecisionRequirementDefinitionsByDeploymentId(deploymentId);
  }

  protected void removeAllProcessDefinitionsByDeploymentId(final String deploymentId) {
    // remove all process definitions for a specific deployment
    final CommandContext commandContext = Context.getCommandContext();

    List<ProcessDefinition> allDefinitionsForDeployment = commandContext.runWithoutAuthorization(new Callable<List<ProcessDefinition>>() {
      public List<ProcessDefinition> call() throws Exception {
        return new ProcessDefinitionQueryImpl()
          .deploymentId(deploymentId)
          .list();
      }
    });

    for (ProcessDefinition processDefinition : allDefinitionsForDeployment) {
      try {
        removeProcessDefinition(processDefinition.getId());

      } catch(Exception e) {
        LOG.removeEntryFromDeploymentCacheFailure("process", processDefinition.getId(), e);
      }
    }
  }

  protected void removeAllCaseDefinitionsByDeploymentId(String deploymentId) {
    // remove all case definitions for a specific deployment

    List<CaseDefinition> allDefinitionsForDeployment = new CaseDefinitionQueryImpl()
        .deploymentId(deploymentId)
        .list();

    for (CaseDefinition caseDefinition : allDefinitionsForDeployment) {
      try {
        removeCaseDefinition(caseDefinition.getId());

      } catch(Exception e) {
        LOG.removeEntryFromDeploymentCacheFailure("case", caseDefinition.getId(), e);
      }
    }
  }

  protected void removeAllDecisionDefinitionsByDeploymentId(String deploymentId) {
    // remove all decision definitions for a specific deployment

    List<DecisionDefinition> allDefinitionsForDeployment = new DecisionDefinitionQueryImpl()
      .deploymentId(deploymentId)
      .list();

    for (DecisionDefinition decisionDefinition : allDefinitionsForDeployment) {
      try {
        removeDecisionDefinition(decisionDefinition.getId());
      } catch(Exception e) {
        LOG.removeEntryFromDeploymentCacheFailure("decision", decisionDefinition.getId(), e);
      }
    }
  }

  protected void removeAllDecisionRequirementDefinitionsByDeploymentId(String deploymentId) {
    // remove all decision requirement definitions for a specific deployment

    List<DecisionRequirementDefinition> allDefinitionsForDeployment = new DecisionRequirementDefinitionQueryImpl()
        .deploymentId(deploymentId)
        .list();

      for (DecisionRequirementDefinition decisionRequirementDefinition : allDefinitionsForDeployment) {
        try {
          removeDecisionDefinition(decisionRequirementDefinition.getId());
        } catch(Exception e) {
          LOG.removeEntryFromDeploymentCacheFailure("decision requirement", decisionRequirementDefinition.getId(), e);
        }
      }
  }

}
