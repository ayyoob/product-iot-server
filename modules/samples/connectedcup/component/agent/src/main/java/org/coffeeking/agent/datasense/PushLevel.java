package org.coffeeking.agent.datasense;

import org.coffeeking.agent.transport.TransportHandlerException;
import org.coffeeking.agent.transport.mqtt.ConnectedCupMQttTransportHandler;
import org.json.JSONObject;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.StreamCorruptedException;

public class PushLevel extends HttpServlet {

    private ConnectedCupMQttTransportHandler connectedCupMQttTransportHandler;

    public PushLevel(){
        connectedCupMQttTransportHandler = ConnectedCupMQttTransportHandler.getInstance();
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        String deviceId = req.getParameter("deviceId");
        String deviceOwner = req.getParameter("deviceOwner");
        String payload = req.getParameter("payload");

        try {
            connectedCupMQttTransportHandler.publishToConnectedCup(deviceOwner,deviceId,payload,0,true);
        } catch (TransportHandlerException e) {
            e.printStackTrace();
        }

    }
}
