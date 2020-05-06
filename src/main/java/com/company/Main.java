package com.company;

import com.github.davidmoten.rtree2.Entry;
import com.github.davidmoten.rtree2.Iterables;
import com.github.davidmoten.rtree2.RTree;
import com.github.davidmoten.rtree2.geometry.Geometries;
import com.github.davidmoten.rtree2.geometry.Geometry;
import com.github.davidmoten.rtree2.geometry.internal.PointDouble;
import com.github.mreutegg.laszip4j.LASHeader;
import com.github.mreutegg.laszip4j.LASPoint;
import com.github.mreutegg.laszip4j.LASReader;
import org.geotools.data.DataStore;
import org.geotools.data.DataStoreFinder;
import org.geotools.data.FeatureSource;
import org.geotools.feature.FeatureCollection;
import org.geotools.feature.FeatureIterator;
import org.geotools.filter.text.cql2.CQL;
import org.geotools.geometry.Envelope2D;
import org.geotools.geometry.jts.JTS;
import org.geotools.referencing.CRS;
import org.geotools.referencing.GeodeticCalculator;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.MultiLineString;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.math.Vector3D;
import org.locationtech.jts.operation.distance.DistanceOp;
import org.opengis.feature.Feature;
import org.opengis.feature.GeometryAttribute;
import org.opengis.geometry.BoundingBox;
import org.opengis.referencing.FactoryException;
import org.opengis.referencing.crs.CoordinateReferenceSystem;
import org.opengis.referencing.operation.MathTransform;
import org.opengis.referencing.operation.TransformException;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

public class Main {

    private static LASReader reader;

    private static RTree<Vector3D, Geometry> terrainTree;

    private static CoordinateReferenceSystem LAZ_CS, SHP_CS;

    public static void main(String[] args) throws FactoryException, IOException, TransformException {


        // Time needed for Delaunay triangulation of file GK_430_136.laz which contained 12M points was 40 minutes on Intel i7 6700K.
        reader = new LASReader(new File("GK_468_104.laz"));

        LASHeader lasHeader = reader.getHeader();
        terrainTree = RTree.create();

        LAZ_CS = CRS.decode("EPSG:3787");

        double left = lasHeader.getMinX();
        double right = lasHeader.getMaxX();
        double top = lasHeader.getMinY();
        double bottom = lasHeader.getMaxY();

        Envelope2D bounds = new Envelope2D();
        bounds.x = left;
        bounds.y = top;
        bounds.width = right - left;
        bounds.height = bottom - top;//left, right, top, bottom, lasHeader.getMinZ(), lasHeader.getMaxZ(), new DefaultDerivedCRS());
        bounds.setCoordinateReferenceSystem(LAZ_CS);


        ArrayList<Bridge> bridges = readShapeFile(new File("../GJI_SLO_SHP_G_1100/GJI_SLO_1100_ILL_20200402.shp"), bounds);

        System.out.println("bridges = " + bridges.size());

        while(!mergeBridges(bridges)) {
            System.out.println("Repeat...");
        }
        System.out.println("bridges = " + bridges.size());

        int i = 0;
        for (Bridge bridge : bridges) {
            BoundingBox bridgeBounds = bridge.getSkirt();
            System.out.println("bridgeBounds = " + bridgeBounds.toString());
            LASReader subread = reader.insideRectangle(bridgeBounds.getMinX(), bridgeBounds.getMinY(), bridgeBounds.getMaxX(), bridgeBounds.getMaxY());

            ArrayList<Vector3D> points = new ArrayList<>();
            for (LASPoint point : subread.getPoints()) {
                points.add(new Vector3D(point.getX(), point.getY(), point.getZ()));
            }


            // TODO: Get bridge width. Maybe even use meters.
            double BRIDGE_WIDTH = 12;

            ArrayList<Vector3D> bridgePoints = new ArrayList<>();
            for (Vector3D vector3D : points) {
                /*if (vector3D.getZ() > minBridgeZ && vector3D.getZ() < maxBridgeZ) {
                    bridgePoints.add(vector3D);
                }*/
                Point point = new GeometryFactory().createPoint(new Coordinate(vector3D.getX() * lasHeader.getXScaleFactor(), vector3D.getY() * lasHeader.getYScaleFactor()));

                MathTransform transform = CRS.findMathTransform(SHP_CS, LAZ_CS, false);

                boolean isTypeBridge = false;
                for(MultiLineString bridgeComponent : bridge.getBridges()) {
                    MultiLineString targetBridge = (MultiLineString) JTS.transform(bridgeComponent, transform);

                    /*
                    GeodeticCalculator gc = new GeodeticCalculator(LAZ_CS);
                    gc.setStartingPosition(JTS.toDirectPosition(DistanceOp.closestPoints(targetBridge, point)[0], LAZ_CS));
                    gc.setDestinationPosition(JTS.toDirectPosition( point.getCoordinate(), LAZ_CS));
                    System.out.println("start = " + gc.getStartingPosition().toString());
                    System.out.println("end = " + gc.getDestinationPosition().toString());
                    double distance = gc.getOrthodromicDistance();
                    */

                    Coordinate coordinate1 = DistanceOp.closestPoints(targetBridge, point)[0];
                    Coordinate coordinate2 = point.getCoordinate();

                    double distance = coordinate1.distance(coordinate2);

                    if (distance < BRIDGE_WIDTH) {
                        isTypeBridge = true;
                        break;
                    }
                }

                if(isTypeBridge) {
                    bridgePoints.add(vector3D);
                }
                else {
                    terrainTree = terrainTree.add(vector3D, PointDouble.create(vector3D.getX(), vector3D.getY()));
                }
            }

            ArrayList<Vector3D> generatedPoints = new ArrayList<>();
            for(Vector3D bridgePoint : bridgePoints) {
                generatedPoints.add(new Vector3D(bridgePoint.getX(), bridgePoint.getY(), interpolateZ(bridgePoint)));
            }
            points.addAll(generatedPoints);
            System.out.println("points = " + points.size());
            System.out.println("bridge points = " + bridgePoints.size());
            points.removeAll(bridgePoints);

            write(points, i++);
        }

    }

    private static boolean mergeBridges(ArrayList<Bridge> bridges) {
        for(int i = 0; i < bridges.size(); i++) {
            for(int j = i + 1; j < bridges.size(); j++) {
                Bridge bridge1 = bridges.get(i);
                Bridge bridge2 = bridges.get(j);
                if(bridge1.getSkirt().intersects(bridge2.getSkirt())) {
                    bridge1.getSkirt().include(bridge2.getSkirt());
                    bridge1.addBridges(bridge2.getBridges());
                    bridges.remove(bridge2);
                    return false;
                }
            }
        }
        return true;
    }

    private static void print(List<Entry<Vector3D, Geometry>> entries) {
        for (Entry<Vector3D, Geometry> entry : entries) {
            System.out.println("z = " + entry.value().getZ());
        }
        System.out.println("----------");
    }

    private static double interpolateZ(Vector3D bridgePoint) {
        List<Entry<Vector3D, Geometry>> entries = Iterables.toList(terrainTree.nearest(Geometries.point(bridgePoint.getX(), bridgePoint.getY()), Double.MAX_VALUE, 15));
        double values = 0;
        double weightsSum = 0;

        // print(entries);
        // Sort by Z value.
        Collections.sort(entries, new Comparator<Entry<Vector3D, Geometry>>() {
            @Override
            public int compare(Entry<Vector3D, Geometry> o1, Entry<Vector3D, Geometry> o2) {
                if(o1.value().getZ() < o2.value().getZ()) {
                    return -1;
                }
                else if(o1.value().getZ() > o2.value().getZ()) {
                    return 1;
                }
                else
                    return 0;
            }
        });
        entries = entries.subList(0, 10);
        /*print(entries);
        if(true)
            System.exit(0);*/

        //System.out.println("size = " + entries.size());
        for (Entry<Vector3D, Geometry> entry : entries) {
            //double newWeight = 1 / distance(entry.value(), bridgePoint);
            double newWeight = 1 / distance(entry.value(), bridgePoint);

            values += newWeight * entry.value().getZ();
            weightsSum += newWeight;
        }
        double z =  values / weightsSum;
        //System.out.println("z = " + z);
        return z;
    }

    private static double distance(Vector3D a, Vector3D b) {
        return Math.sqrt(Math.pow(a.getX() - b.getX(), 2) + Math.pow(a.getY() - b.getY(), 2));
    }

    private static void write(ArrayList<Vector3D> points, int number) {
        try (Writer writer = new BufferedWriter(new OutputStreamWriter(
                new FileOutputStream(String.format("test%d.obj", number)), StandardCharsets.UTF_8))) {
            for(Vector3D lasPoint : points) {
                writer.write("v " + lasPoint.getX() + " " + lasPoint.getY() + " " + lasPoint.getZ() + "\n");
            }
        }
        catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static ArrayList<Bridge> readShapeFile(File file, Envelope2D bounds) {
        ArrayList<Bridge> result = new ArrayList<>();

        try {
            Map<String, String> connect = new HashMap();
            connect.put("url", file.toURI().toString());

            DataStore dataStore = DataStoreFinder.getDataStore(connect);
            String[] typeNames = dataStore.getTypeNames();
            String typeName = typeNames[0];

            System.out.println("Reading content " + typeName);

            FeatureSource featureSource = dataStore.getFeatureSource(typeName);

            // Only get the bridges.
            String filterStatement = "\"SIF_VRSTE\" = 1102 AND \"ATR2\" = 1";
            //String filterStatement = "\"SIF_VRSTE\" = 1102 AND \"ATR2\" = 1 AND ID = 17530217";

            FeatureCollection collection = featureSource.getFeatures().subCollection(CQL.toFilter(filterStatement));
            FeatureIterator iterator = collection.features();

            try {
                while (iterator.hasNext()) {
                    Feature feature = iterator.next();
                    GeometryAttribute sourceGeometry = feature.getDefaultGeometryProperty();

                    if(SHP_CS == null) {
                        SHP_CS = sourceGeometry.getBounds().getCoordinateReferenceSystem();
                    }
                    //if(bounds.intersects(sourceGeometry.getBounds()))

                    //System.out.println("type = " + sourceGeometry.getBounds().toString());
                    //System.out.println("bbox = " + bounds.toString());


                    CoordinateReferenceSystem coordinateReferenceSystem = sourceGeometry.getBounds().getCoordinateReferenceSystem();
                    BoundingBox bridgeBox = sourceGeometry.getBounds();
                    BoundingBox lasBox = bounds.toBounds(coordinateReferenceSystem);


                    if(bridgeBox.intersects(lasBox)) {
                        System.out.println("FOUND!");
                        Bridge bridge = new Bridge();
                        if(sourceGeometry.getValue() instanceof MultiLineString) {
                            MultiLineString multiLineString = (MultiLineString) sourceGeometry.getValue();
                            bridge.addBridge(multiLineString);
                        }

                        bridge.setSkirt(bridgeBox.toBounds(LAZ_CS));
                        result.add(bridge);
                    }
                }
            } finally {
                iterator.close();
            }


        }
        catch (Throwable e) {
            e.printStackTrace();
        }
        System.out.println("length = " + result.size());
        return result;
    }


}
