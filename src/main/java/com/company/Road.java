package com.company;

import org.locationtech.jts.geom.MultiLineString;

public class Road {
    private long id;

    public Road(long id) {
        this.id = id;
    }

    public long getId() {
        return id;
    }

    private MultiLineString road;
    private double width;

    public void setWidth(double width) {
        this.width = width;
    }

    public double getWidth() {
        return width;
    }

    public MultiLineString getRoad() {
        return road;
    }

    public void setRoad(MultiLineString road) {
        this.road = road;
    }
}
