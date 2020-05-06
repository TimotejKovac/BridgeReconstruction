package com.company;

import org.locationtech.jts.geom.LinearRing;
import org.locationtech.jts.geom.MultiLineString;
import org.opengis.geometry.BoundingBox;

import java.util.ArrayList;

public class Bridge {

    private ArrayList<MultiLineString> bridges;
    private BoundingBox skirt;

    public Bridge() {
        bridges = new ArrayList<>();
    }

    public void addBridge(MultiLineString bridge) {
        this.bridges.add(bridge);
    }

    public void addBridges(ArrayList<MultiLineString> bridges) {
        this.bridges.addAll(bridges);
    }

    public ArrayList<MultiLineString> getBridges() {
        return bridges;
    }

    public BoundingBox getSkirt() {
        return skirt;
    }

    public void setSkirt(BoundingBox skirt) {
        this.skirt = skirt;
    }
}
