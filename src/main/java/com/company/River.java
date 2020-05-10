package com.company;

import org.locationtech.jts.geom.MultiLineString;

public class River {
    private long id;

    public River(long id) {
        this.id = id;
    }

    public long getId() {
        return id;
    }

    private MultiLineString river;
    private double width;

    public void setWidth(double width) {
        this.width = width;
    }

    public double getWidth() {
        return width;
    }

    public MultiLineString getRiver() {
        return river;
    }

    public void setRiver(MultiLineString road) {
        this.river = road;
    }
}
