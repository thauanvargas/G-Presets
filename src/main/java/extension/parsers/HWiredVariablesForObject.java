package extension.parsers;

import gearth.protocol.HPacket;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class HWiredVariablesForObject {

    public static final int TYPE_OBJECT = 0;
    public static final int TYPE_USER = 1;

    public final int type;
    public final int objectId;
    public final int userIndex;
    public final Map<String, Integer> variables;
    public final List<Integer> configuredInWireds;

    public HWiredVariablesForObject(HPacket packet) {
        type = packet.readInteger();

        if (type == TYPE_OBJECT) {
            objectId = packet.readInteger();
            userIndex = -1;
        }
        else if (type == TYPE_USER) {
            userIndex = packet.readInteger();
            objectId = -1;
        }
        else {
            objectId = -1;
            userIndex = -1;
        }

        variables = new HashMap<>();
        int count = packet.readInteger();
        for (int i = 0; i < count; i++) {
            variables.put(packet.readString(), packet.readInteger());
        }

        if (type == TYPE_OBJECT) {
            int wiredCount = packet.readInteger();
            configuredInWireds = new ArrayList<>(wiredCount);
            for (int i = 0; i < wiredCount; i++) {
                configuredInWireds.add(packet.readInteger());
            }
        }
        else {
            configuredInWireds = new ArrayList<>();
        }
    }
}