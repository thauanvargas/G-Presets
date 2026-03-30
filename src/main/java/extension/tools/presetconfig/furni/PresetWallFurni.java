package extension.tools.presetconfig.furni;

import extension.tools.presetconfig.PresetJsonConfigurable;
import org.json.JSONObject;
import utils.WallPosition;

public class PresetWallFurni implements PresetJsonConfigurable {

    private int furniId;
    private String className;
    private WallPosition location;
    private String state;

    private String furniName = null;

    public PresetWallFurni(int furniId, String className, WallPosition location, String state) {
        this.furniId = furniId;
        this.className = className;
        this.location = location;
        this.state = state;
    }

    public PresetWallFurni(PresetWallFurni furni) {
        this.furniId = furni.furniId;
        this.className = furni.className;
        this.location = new WallPosition(
                furni.location.getX(),
                furni.location.getY(),
                furni.location.getOffsetX(),
                furni.location.getOffsetY(),
                furni.location.getDirection(),
                furni.location.getAltitude()
        );
        this.state = furni.state;
        this.furniName = furni.furniName;
    }

    public PresetWallFurni(JSONObject jsonObject) {
        this.furniId = jsonObject.getInt("id");
        this.className = jsonObject.getString("className");
        JSONObject loc = jsonObject.getJSONObject("location");
        this.location = new WallPosition(
                loc.getInt("x"),
                loc.getInt("y"),
                loc.getInt("offsetX"),
                loc.getInt("offsetY"),
                loc.getString("direction").charAt(0),
                loc.getInt("altitude")
        );
        this.state = jsonObject.has("state") ? jsonObject.getString("state") : null;
        this.furniName = jsonObject.getString("name");
    }

    @Override
    public JSONObject toJsonObject() {
        JSONObject object = new JSONObject();
        object.put("id", furniId);
        object.put("className", className);

        JSONObject jsonLocation = new JSONObject();
        jsonLocation.put("x", location.getX());
        jsonLocation.put("y", location.getY());
        jsonLocation.put("offsetX", location.getOffsetX());
        jsonLocation.put("offsetY", location.getOffsetY());
        jsonLocation.put("direction", String.valueOf(location.getDirection()));
        jsonLocation.put("altitude", location.getAltitude());
        object.put("location", jsonLocation);

        if (state != null) {
            object.put("state", state);
        }

        object.put("name", furniName);

        return object;
    }

    public int getFurniId() {
        return furniId;
    }

    public void setFurniId(int furniId) {
        this.furniId = furniId;
    }

    public String getClassName() {
        return className;
    }

    public void setClassName(String className) {
        this.className = className;
    }

    public WallPosition getLocation() {
        return location;
    }

    public void setLocation(WallPosition location) {
        this.location = location;
    }

    public String getState() {
        return state;
    }

    public void setState(String state) {
        this.state = state;
    }

    public String getFurniName() {
        return furniName;
    }

    public void setFurniName(String furniName) {
        this.furniName = furniName;
    }
}
