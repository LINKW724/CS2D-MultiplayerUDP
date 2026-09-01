package cs2d.AIControl.route;

/** Route categories persisted by the map route editor. */
public enum RouteType {
    TDM_T_CT("presetPathsTtoCT", "T", true),
    TDM_CT_T("presetPathsCTtoT", "CT", true),
    DEMO_CT_A("presetPathsCTtoA", "CT", false),
    DEMO_CT_B("presetPathsCTtoB", "CT", false),
    DEMO_T_A("presetPathsTtoA", "T", false),
    DEMO_T_B("presetPathsTtoB", "T", false);

    private final String jsonField;
    private final String teamId;
    private final boolean tdm;

    RouteType(String jsonField, String teamId, boolean tdm) {
        this.jsonField = jsonField;
        this.teamId = teamId;
        this.tdm = tdm;
    }

    public String jsonField() {
        return jsonField;
    }

    public String teamId() {
        return teamId;
    }

    public boolean isTdm() {
        return tdm;
    }
}
