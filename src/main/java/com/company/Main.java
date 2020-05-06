package com.company;

import com.github.mreutegg.laszip4j.LASReader;
import org.geotools.data.DataStore;
import org.geotools.data.DataStoreFinder;
import org.geotools.data.FeatureSource;
import org.geotools.feature.FeatureCollection;
import org.geotools.feature.FeatureIterator;
import org.opengis.feature.Feature;
import org.opengis.feature.GeometryAttribute;


import java.io.*;
import java.util.*;

public class Main {

    static LASReader reader;

    public static void main(String[] args) {


        // Time needed for Delaunay triangulation of file GK_430_136.laz which contained 12M points was 40 minutes on Intel i7 6700K.
        reader = new LASReader(new File("GK_468_104.laz"));


        // TODO: Read shape file and get all of the bridges
        ArrayList<GeometryAttribute> bridges = readShapeFile(new File("../GJI_SLO_SHP_G_1100/GJI_SLO_1100_ILL_20200402.shp"));




        // TODO: Get all of the areas and bounding boxes of all the bridges

        // TODO: For every area calculate the normals of the points.
        // TODO: Construct rays for every deltax path and send them through. See if there is a way (mark them). Otherwise make points.
    }

    private static ArrayList<GeometryAttribute> readShapeFile(File file) {
        ArrayList<GeometryAttribute> result = new ArrayList<GeometryAttribute>();

        try {
            Map<String, String> connect = new HashMap();
            connect.put("url", file.toURI().toString());

            DataStore dataStore = DataStoreFinder.getDataStore(connect);
            String[] typeNames = dataStore.getTypeNames();
            String typeName = typeNames[0];

            System.out.println("Reading content " + typeName);

            FeatureSource featureSource = dataStore.getFeatureSource(typeName);
            FeatureCollection collection = featureSource.getFeatures();
            FeatureIterator iterator = collection.features();


            try {
                while (iterator.hasNext()) {
                    Feature feature = iterator.next();
                    GeometryAttribute sourceGeometry = feature.getDefaultGeometryProperty();
                    System.out.println("source geometry = " + sourceGeometry.toString());
                }
            } finally {
                iterator.close();
            }

        } catch (Throwable e) {}
        return result;
    }


}
