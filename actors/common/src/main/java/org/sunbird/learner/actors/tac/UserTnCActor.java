package org.sunbird.learner.actors.tac;

import java.sql.Timestamp;
import java.util.*;
import org.apache.commons.lang3.StringUtils;
import org.sunbird.actor.core.BaseActor;
import org.sunbird.actor.router.ActorConfig;
import org.sunbird.cassandra.CassandraOperation;
import org.sunbird.common.ElasticSearchUtil;
import org.sunbird.common.exception.ProjectCommonException;
import org.sunbird.common.models.response.Response;
import org.sunbird.common.models.util.ActorOperations;
import org.sunbird.common.models.util.JsonKey;
import org.sunbird.common.models.util.ProjectLogger;
import org.sunbird.common.models.util.ProjectUtil;
import org.sunbird.common.request.ExecutionContext;
import org.sunbird.common.request.Request;
import org.sunbird.common.responsecode.ResponseCode;
import org.sunbird.helper.ServiceFactory;
import org.sunbird.learner.util.Util;
import org.sunbird.telemetry.util.TelemetryUtil;

@ActorConfig(
  tasks = {"userTnCAccept"},
  asyncTasks = {}
)
public class UserTnCActor extends BaseActor {
  private CassandraOperation cassandraOperation = ServiceFactory.getInstance();
  private Util.DbInfo usrDbInfo = Util.dbInfoMap.get(JsonKey.USER_DB);

  @Override
  public void onReceive(Request request) throws Throwable {
    String operation = request.getOperation();
    ExecutionContext.setRequestId(request.getRequestId());

    if (operation.equalsIgnoreCase(ActorOperations.USER_TNC_ACCEPT.getValue())) {
      acceptTNC(request);
    } else {
      onReceiveUnsupportedOperation("UserTnCActor");
    }
  }

  private void acceptTNC(Request request) {
    String acceptedTnC = (String) request.getRequest().get(JsonKey.VERSION);
    Map<String, Object> userMap = new HashMap();
    String userId = (String) request.getContext().get(JsonKey.REQUESTED_BY);
    Map<String, Object> result =
        ElasticSearchUtil.getDataByIdentifier(
            ProjectUtil.EsIndex.sunbird.getIndexName(),
            ProjectUtil.EsType.user.getTypeName(),
            userId);
    if (result == null || result.size() == 0) {
      throw new ProjectCommonException(
          ResponseCode.userNotFound.getErrorCode(),
          ResponseCode.userNotFound.getErrorMessage(),
          ResponseCode.RESOURCE_NOT_FOUND.getResponseCode());
    }
    // check whether is_deletd true or false
    if (ProjectUtil.isNotNull(result)
        && result.containsKey(JsonKey.IS_DELETED)
        && ProjectUtil.isNotNull(result.get(JsonKey.IS_DELETED))
        && (Boolean) result.get(JsonKey.IS_DELETED)) {
      ProjectCommonException.throwClientErrorException(ResponseCode.userAccountlocked);
    }

    String lastAcceptedVersion = (String) result.get(JsonKey.TNC_ACCEPTED_VERSION);
    Response response = new Response();
    if (StringUtils.isEmpty(lastAcceptedVersion)
        || !lastAcceptedVersion.equalsIgnoreCase(acceptedTnC)
        || StringUtils.isEmpty((String) result.get(JsonKey.TNC_ACCEPTED_ON))) {
      userMap.put(JsonKey.ID, userId);
      userMap.put(JsonKey.TNC_ACCEPTED_VERSION, acceptedTnC);
      userMap.put(
          JsonKey.TNC_ACCEPTED_ON, new Timestamp(Calendar.getInstance().getTime().getTime()));
      response =
          cassandraOperation.updateRecord(
              usrDbInfo.getKeySpace(), usrDbInfo.getTableName(), userMap);
      if (((String) response.get(JsonKey.RESPONSE)).equalsIgnoreCase(JsonKey.SUCCESS)) {
        saveUserDetailsToEs(userMap);
      }
    }
    response.getResult().put(JsonKey.RESULT, "SUCCESS");
    sender().tell(response, self());
    Map<String, Object> targetObject = null;
    List<Map<String, Object>> correlatedObject = new ArrayList<>();
    targetObject =
        TelemetryUtil.generateTargetObject(
            (String) userMap.get(JsonKey.USER_ID), JsonKey.TNC, JsonKey.ACCEPT, null);
    TelemetryUtil.telemetryProcessingCall(userMap, targetObject, correlatedObject);
  }

  private void saveUserDetailsToEs(Map<String, Object> completeUserMap) {
    Request userRequest = new Request();
    userRequest.setOperation(ActorOperations.UPDATE_USER_INFO_ELASTIC.getValue());
    userRequest.getRequest().put(JsonKey.ID, completeUserMap.get(JsonKey.ID));
    ProjectLogger.log("UserTnCActor:saveUserDetailsToEs: Trigger sync of user details to ES");
    tellToAnother(userRequest);
  }
}
