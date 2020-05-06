package com.company;

import org.locationtech.jts.geom.LinearRing;
import org.locationtech.jts.geom.MultiLineString;
import org.opengis.geometry.BoundingBox;

public class Bridge {

    private MultiLineString bridge;
    private BoundingBox skirt;

    public Bridge() {

    }

    public Bridge(MultiLineString bridge, BoundingBox skirt) {
        this.bridge = bridge;
        this.skirt = skirt;
    }

    public void setBridge(MultiLineString bridge) {
        this.bridge = bridge;
    }

    public MultiLineString getBridge() {
        return bridge;
    }

    public BoundingBox getSkirt() {
        return skirt;
    }

    public void setSkirt(BoundingBox skirt) {
        this.skirt = skirt;
    }
}
