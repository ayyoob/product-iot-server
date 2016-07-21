/*
* Copyright (c) 2016, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
*
* WSO2 Inc. licenses this file to you under the Apache License,
* Version 2.0 (the "License"); you may not use this file except
* in compliance with the License.
* You may obtain a copy of the License at
*
* http://www.apache.org/licenses/LICENSE-2.0
*
* Unless required by applicable law or agreed to in writing,
* software distributed under the License is distributed on an
* "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
* KIND, either express or implied. See the License for the
* specific language governing permissions and limitations
* under the License.
*/

package org.homeautomation.firealarm.api;

import org.apache.commons.io.FileUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.homeautomation.firealarm.api.dto.DeviceJSON;
import org.homeautomation.firealarm.api.dto.SensorRecord;
import org.homeautomation.firealarm.api.util.APIUtil;
import org.homeautomation.firealarm.api.util.ZipUtil;
import org.homeautomation.firealarm.plugin.constants.DeviceTypeConstants;
import org.wso2.carbon.analytics.dataservice.commons.SORT;
import org.wso2.carbon.analytics.dataservice.commons.SortByField;
import org.wso2.carbon.analytics.datasource.commons.exception.AnalyticsException;
import org.wso2.carbon.apimgt.annotations.api.API;
import org.wso2.carbon.apimgt.application.extension.APIManagementProviderService;
import org.wso2.carbon.apimgt.application.extension.dto.ApiApplicationKey;
import org.wso2.carbon.apimgt.application.extension.exception.APIManagerException;
import org.wso2.carbon.context.PrivilegedCarbonContext;
import org.wso2.carbon.device.mgt.common.Device;
import org.wso2.carbon.device.mgt.common.DeviceIdentifier;
import org.wso2.carbon.device.mgt.common.DeviceManagementException;
import org.wso2.carbon.device.mgt.common.EnrolmentInfo;
import org.wso2.carbon.device.mgt.common.authorization.DeviceAccessAuthorizationException;
import org.wso2.carbon.device.mgt.extensions.feature.mgt.annotations.DeviceType;
import org.wso2.carbon.device.mgt.extensions.feature.mgt.annotations.Feature;
import org.wso2.carbon.device.mgt.iot.util.ZipArchive;
import org.wso2.carbon.identity.jwt.client.extension.JWTClient;
import org.wso2.carbon.identity.jwt.client.extension.dto.AccessTokenInfo;
import org.wso2.carbon.identity.jwt.client.extension.exception.JWTClientException;
import org.wso2.carbon.user.api.UserStoreException;

import javax.servlet.http.HttpServletResponse;
import javax.ws.rs.Consumes;
import javax.ws.rs.DELETE;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.PUT;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * This is the API which is used to control and manage device type functionality.
 */
@SuppressWarnings("NonJaxWsWebServices")
@API(name = "firealarm", version = "1.0.0", context = "/firealarm", tags = "firealarm")
@DeviceType(value = "firealarm")
public class DeviceTypeServiceImpl implements DeviceTypeService {

    private static final String KEY_TYPE = "PRODUCTION";
    private static Log log = LogFactory.getLog(DeviceTypeService.class);
    private static ApiApplicationKey apiApplicationKey;

    private static String shortUUID() {
        UUID uuid = UUID.randomUUID();
        long l = ByteBuffer.wrap(uuid.toString().getBytes(StandardCharsets.UTF_8)).getLong();
        return Long.toString(l, Character.MAX_RADIX);
    }

    /**
     * Validate registration information.
     *
     * @param agentInfo device owner,id.
     * @return true if device instance is added to map.
     */
    @Path("device/register")
    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    public Response registerDevice(final DeviceJSON agentInfo) {
        String deviceId = agentInfo.deviceId;
        if ((agentInfo.deviceId != null) && (agentInfo.owner != null)) {
            return Response.status(Response.Status.OK).build();
        }
        return Response.status(Response.Status.NOT_ACCEPTABLE).build();
    }

    /**
     * Change buzzer status.
     *
     * @param deviceId unique identifier for given device type.
     * @param state    change status of buzzer: on/off.
     * @param response operation is success or not.
     */
    @Path("device/{deviceId}/change-status")
    @POST
    @Feature(code = "change-status", name = "Change status of sensor: on/off",
            description = "Change status of sensor: on/off")
    public Response changeStatus(@PathParam("deviceId") String deviceId,
                                 @QueryParam("state") String state,
                                 @Context HttpServletResponse response) {
        try {
            if (!APIUtil.getDeviceAccessAuthorizationService()
                    .isUserAuthorized(new DeviceIdentifier(deviceId, DeviceTypeConstants.DEVICE_TYPE))) {
                return Response.status(Response.Status.UNAUTHORIZED).build();
            }
            if (state == null) {
                return Response.status(Response.Status.BAD_REQUEST).build();
            }
            String sensorState = state.toUpperCase();
            if (!DeviceTypeConstants.STATE_ON.equals(sensorState)
                    && !sensorState.equals(DeviceTypeConstants.STATE_OFF)) {
                log.error("The requested state change should be either - 'ON' or 'OFF'");
                return Response.status(Response.Status.BAD_REQUEST).build();
            }
            Map<String, String> dynamicProperties = new HashMap<>();
            String publishTopic = APIUtil.getAuthenticatedUserTenantDomain()
                                  + "/" + DeviceTypeConstants.DEVICE_TYPE + "/" + deviceId + "/command";
            dynamicProperties.put(DeviceTypeConstants.ADAPTER_TOPIC_PROPERTY, publishTopic);
            APIUtil.getOutputEventAdapterService().publish(DeviceTypeConstants.MQTT_ADAPTER_NAME,
                                                           dynamicProperties, state);
            return Response.ok().build();
        } catch (DeviceAccessAuthorizationException e) {
            log.error("Unable to change buzzer status", e);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR).entity(e.getMessage()).build();
        }
    }

    /**
     * Retrieve Sensor data for the given time period.
     *
     * @param deviceId   unique identifier for given device type instance.
     * @param sensorName name of the sensor.
     * @param from       starting time.
     * @param to         ending time.
     * @return response with List<SensorRecord> object which includes sensor data which is requested.
     */
    @Path("device/stats/{deviceId}/sensors/{sensorName}")
    @GET
    @Consumes("application/json")
    @Produces("application/json")
    public Response getSensorStats(@PathParam("deviceId") String deviceId, @PathParam("sensorName") String sensorName,
                                   @QueryParam("from") long from, @QueryParam("to") long to) {
        String fromDate = String.valueOf(from);
        String toDate = String.valueOf(to);
        String query = "deviceId:" + deviceId + " AND deviceType:" +
                       DeviceTypeConstants.DEVICE_TYPE + " AND time : [" + fromDate + " TO " + toDate + "]";
        String sensorTableName;
        switch (sensorName) {
            case DeviceTypeConstants.STREAM_TEMPERATURE:
                sensorTableName = DeviceTypeConstants.TEMPERATURE_EVENT_TABLE;
                break;
            case DeviceTypeConstants.STREAM_HUMIDITY:
                sensorTableName = DeviceTypeConstants.HUMIDITY_EVENT_TABLE;
                break;
            default:
                return Response.status(Response.Status.BAD_REQUEST).entity("Invalid event stream").build();
        }

        try {
            if (!APIUtil.getDeviceAccessAuthorizationService()
                    .isUserAuthorized(new DeviceIdentifier(deviceId, DeviceTypeConstants.DEVICE_TYPE))) {
                return Response.status(Response.Status.UNAUTHORIZED).build();
            }
            List<SortByField> sortByFields = new ArrayList<>();
            SortByField sortByField = new SortByField("time", SORT.ASC, false);
            sortByFields.add(sortByField);
            List<SensorRecord> sensorRecords = APIUtil.getAllEventsForDevice(sensorTableName, query, sortByFields);
            return Response.status(Response.Status.OK).entity(sensorRecords).build();
        } catch (AnalyticsException e) {
            String errorMsg = "Error on retrieving stats on table " + sensorTableName + " with query " + query;
            log.error(errorMsg);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR).entity(errorMsg).build();
        } catch (DeviceAccessAuthorizationException e) {
            log.error("Unable to get sensor stats", e);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR).entity(e.getMessage()).build();
        }
    }

    /**
     * Remove device type instance using device id.
     *
     * @param deviceId unique identifier for given device type instance.
     */
    @Path("/device/{deviceId}")
    @DELETE
    public Response removeDevice(@PathParam("deviceId") String deviceId) {
        try {
            DeviceIdentifier deviceIdentifier = new DeviceIdentifier();
            deviceIdentifier.setId(deviceId);
            deviceIdentifier.setType(DeviceTypeConstants.DEVICE_TYPE);
            if (!APIUtil.getDeviceAccessAuthorizationService().isUserAuthorized(deviceIdentifier)) {
                return Response.status(Response.Status.UNAUTHORIZED).build();
            }
            boolean removed = APIUtil.getDeviceManagementService().disenrollDevice(
                    deviceIdentifier);
            if (removed) {
                return Response.ok().build();
            } else {
                return Response.status(Response.Status.NOT_ACCEPTABLE).build();
            }
        } catch (DeviceManagementException | DeviceAccessAuthorizationException e) {
            log.error("Unable to remove device", e);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR).build();
        }
    }

    /**
     * Update device instance name.
     *
     * @param deviceId unique identifier for given device type instance.
     * @param name     new name for the device type instance.
     */
    @Path("/device/{deviceId}")
    @PUT
    public Response updateDevice(@PathParam("deviceId") String deviceId, @QueryParam("name") String name) {
        try {
            DeviceIdentifier deviceIdentifier = new DeviceIdentifier();
            deviceIdentifier.setId(deviceId);
            deviceIdentifier.setType(DeviceTypeConstants.DEVICE_TYPE);
            if (!APIUtil.getDeviceAccessAuthorizationService().isUserAuthorized(deviceIdentifier)) {
                return Response.status(Response.Status.UNAUTHORIZED).build();
            }
            Device device = APIUtil.getDeviceManagementService().getDevice(deviceIdentifier);
            device.setDeviceIdentifier(deviceId);
            device.getEnrolmentInfo().setDateOfLastUpdate(new Date().getTime());
            device.setName(name);
            device.setType(DeviceTypeConstants.DEVICE_TYPE);
            boolean updated = APIUtil.getDeviceManagementService().modifyEnrollment(device);
            if (updated) {
                return Response.ok().build();
            } else {
                return Response.status(Response.Status.NOT_ACCEPTABLE).build();
            }
        } catch (DeviceManagementException | DeviceAccessAuthorizationException e) {
            log.error("Unable to update device", e);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR).entity(e.getMessage()).build();
        }
    }

    /**
     * To get device information.
     *
     * @param deviceId unique identifier for given device type instance.
     * @return firealarm device.
     */
    @Path("/device/{deviceId}")
    @GET
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response getDevice(@PathParam("deviceId") String deviceId) {
        try {
            DeviceIdentifier deviceIdentifier = new DeviceIdentifier();
            deviceIdentifier.setId(deviceId);
            deviceIdentifier.setType(DeviceTypeConstants.DEVICE_TYPE);
            if (!APIUtil.getDeviceAccessAuthorizationService().isUserAuthorized(deviceIdentifier)) {
                return Response.status(Response.Status.UNAUTHORIZED).build();
            }
            Device device = APIUtil.getDeviceManagementService().getDevice(deviceIdentifier);
            return Response.ok().entity(device).build();
        } catch (DeviceManagementException | DeviceAccessAuthorizationException e) {
            log.error("Unable to get device", e);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR).build();
        }
    }

    /**
     * Get all device type instance which belongs to user.
     *
     * @return Array of devices which includes device's information.
     */
    @Path("/devices")
    @GET
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response getAllDevices() {
        try {
            List<Device> userDevices =
                    APIUtil.getDeviceManagementService().getDevicesOfUser(APIUtil.getAuthenticatedUser());
            ArrayList<Device> userDevicesforFirealarm = new ArrayList<>();
            for (Device device : userDevices) {
                if (device.getType().equals(DeviceTypeConstants.DEVICE_TYPE) &&
                    device.getEnrolmentInfo().getStatus().equals(EnrolmentInfo.Status.ACTIVE)) {
                    userDevicesforFirealarm.add(device);
                }
            }
            Device[] devices = userDevicesforFirealarm.toArray(new Device[]{});
            return Response.ok().entity(devices).build();
        } catch (DeviceManagementException e) {
            log.error("Unable to get all devices", e);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR).build();
        }
    }

    /**
     * To download device type agent source code as zip file.
     *
     * @param deviceName name for the device type instance.
     * @param sketchType folder name where device type agent was installed into server.
     * @return Agent source code as zip file.
     */
    @Path("/device/download")
    @GET
    @Produces("application/zip")
    public Response downloadSketch(@QueryParam("deviceName") String deviceName,
                                   @QueryParam("sketchType") String sketchType) {
        try {
            ZipArchive zipFile = createDownloadFile(APIUtil.getAuthenticatedUser(), deviceName, sketchType);
            Response.ResponseBuilder response = Response.ok(FileUtils.readFileToByteArray(zipFile.getZipFile()));
            response.status(Response.Status.OK);
            response.type("application/zip");
            response.header("Content-Disposition", "attachment; filename=\"" + zipFile.getFileName() + "\"");
            Response resp = response.build();
            zipFile.getZipFile().delete();
            return resp;
        } catch (IllegalArgumentException ex) {
            return Response.status(Response.Status.BAD_REQUEST).entity(ex.getMessage()).build();//bad request
        } catch (DeviceManagementException | JWTClientException | APIManagerException | IOException
                | UserStoreException ex) {
            log.error("Unable to download sketch", ex);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR).entity(ex.getMessage()).build();
        }
    }

    /**
     * Register device into device management service.
     *
     * @param deviceId unique identifier for given device type instance.
     * @param name     name for the device type instance.
     * @return whether device is installed into cdmf or not.
     */
    private boolean register(String deviceId, String name) throws DeviceManagementException {
        DeviceIdentifier deviceIdentifier = new DeviceIdentifier();
        deviceIdentifier.setId(deviceId);
        deviceIdentifier.setType(DeviceTypeConstants.DEVICE_TYPE);
        if (APIUtil.getDeviceManagementService().isEnrolled(deviceIdentifier)) {
            return false;
        }
        Device device = new Device();
        device.setDeviceIdentifier(deviceId);
        EnrolmentInfo enrolmentInfo = new EnrolmentInfo();
        enrolmentInfo.setDateOfEnrolment(new Date().getTime());
        enrolmentInfo.setDateOfLastUpdate(new Date().getTime());
        enrolmentInfo.setStatus(EnrolmentInfo.Status.ACTIVE);
        enrolmentInfo.setOwnership(EnrolmentInfo.OwnerShip.BYOD);
        device.setName(name);
        device.setType(DeviceTypeConstants.DEVICE_TYPE);
        enrolmentInfo.setOwner(APIUtil.getAuthenticatedUser());
        device.setEnrolmentInfo(enrolmentInfo);
        boolean added = APIUtil.getDeviceManagementService().enrollDevice(device);
        if (added) {
            APIUtil.registerApiAccessRoles(APIUtil.getAuthenticatedUser());
        }
        return added;
    }

    /**
     * Generates zip archive with the device agent.
     *
     * @param owner      of the device.
     * @param deviceName given to the device.
     * @param sketchType of the device.
     * @return zip archive to download.
     * @throws DeviceManagementException
     * @throws JWTClientException
     * @throws APIManagerException
     * @throws UserStoreException
     */
    private ZipArchive createDownloadFile(String owner, String deviceName, String sketchType)
            throws DeviceManagementException, JWTClientException, APIManagerException,
                   UserStoreException {
        //create new device id
        String deviceId = shortUUID();
        if (apiApplicationKey == null) {
            String applicationUsername = PrivilegedCarbonContext.getThreadLocalCarbonContext().getUserRealm()
                    .getRealmConfiguration().getAdminUserName();
            applicationUsername = applicationUsername + "@" + APIUtil.getAuthenticatedUserTenantDomain();
            APIManagementProviderService apiManagementProviderService = APIUtil.getAPIManagementProviderService();
            String[] tags = {DeviceTypeConstants.DEVICE_TYPE};
            apiApplicationKey = apiManagementProviderService.generateAndRetrieveApplicationKeys(
                    DeviceTypeConstants.DEVICE_TYPE, tags, KEY_TYPE, applicationUsername, true);
        }
        JWTClient jwtClient = APIUtil.getJWTClientManagerService().getJWTClient();
        String scopes = "device_type_" + DeviceTypeConstants.DEVICE_TYPE + " device_" + deviceId;
        AccessTokenInfo accessTokenInfo = jwtClient.getAccessToken(apiApplicationKey.getConsumerKey(),
                                                                   apiApplicationKey.getConsumerSecret(),
                                                                   owner + "@" + APIUtil.getAuthenticatedUserTenantDomain(),
                                                                   scopes);
        //create token
        String accessToken = accessTokenInfo.getAccessToken();
        String refreshToken = accessTokenInfo.getRefreshToken();
        boolean status = register(deviceId, deviceName);
        if (!status) {
            String msg = "Error occurred while registering the device with " + "id: " + deviceId + " owner:" + owner;
            throw new DeviceManagementException(msg);
        }
        ZipUtil ziputil = new ZipUtil();
        return ziputil.createZipFile(owner, APIUtil.getTenantDomainOftheUser(), sketchType,
                                     deviceId, deviceName, accessToken, refreshToken);
    }

}
