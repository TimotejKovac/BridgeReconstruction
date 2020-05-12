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
import org.locationtech.jts.geom.*;
import org.opengis.feature.Feature;
import org.opengis.feature.GeometryAttribute;
import org.opengis.geometry.BoundingBox;
import org.opengis.referencing.FactoryException;
import org.opengis.referencing.crs.CoordinateReferenceSystem;
import org.opengis.referencing.operation.MathTransform;
import org.opengis.referencing.operation.TransformException;

import javax.vecmath.Vector3d;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.Collectors;

import static com.company.Main.TYPE.*;

public class Main {

    private static final String INFRASTRUCTURE_FILE = "../GJI_SLO_SHP_G_1100_D48/GJI_SLO_1100_ILL_D48_20200508.shp";
    private static final String ROADS_FILE = "../DTM_TN_D48/TN_CESTE_L.shp";
    private static final String WATERS_FILE = "../DTM_HY_D48/HY_TEKOCE_VODE_L.shp";

    private static LASReader reader;

    private static RTree<Vector3d, Geometry> terrainTree, bridgeTree;

    private static double scaleFactorX, scaleFactorY;

    private static GeometryFactory geometryFactory;

    private static List<Road> roads;


    private static HashMap<Long, long[]> dataIds = new HashMap<>();


    public static void main(String[] args) throws FactoryException, IOException, TransformException {


        // Time needed for Delaunay triangulation of file GK_430_136.laz which contained 12M points was 40 minutes on Intel i7 6700K.
        reader = new LASReader(new File("GK_468_104.laz"));

        LASHeader lasHeader = reader.getHeader();
        scaleFactorX = lasHeader.getXScaleFactor();
        scaleFactorY = lasHeader.getYScaleFactor();

        geometryFactory = new GeometryFactory();

        terrainTree = RTree.create();
        bridgeTree = RTree.create();

        dataIds.put(17530217L, new long[] {469434, 458364});
        dataIds.put(17530216L, new long[] {477696, 458364});
        dataIds.put(17530116L, new long[] {27611, 458366});
        dataIds.put(17530215L, new long[] {477693, 458347});


        double left = lasHeader.getMinX();
        double right = lasHeader.getMaxX();
        double top = lasHeader.getMinY();
        double bottom = lasHeader.getMaxY();

        Envelope2D bounds = new Envelope2D();
        bounds.x = left;
        bounds.y = top;
        bounds.width = right - left;
        bounds.height = bottom - top;
        //bounds.setCoordinateReferenceSystem(LAZ_CS);

        String roadsFilter = "";
        String riverFilter = "";
        for(Map.Entry<Long, long[]> entry : dataIds.entrySet()) {
            if(riverFilter.length() > 0) {
                roadsFilter += " OR ";
                riverFilter += " OR ";
            }
            roadsFilter += "\"TN_DTM_ID\"= " + entry.getValue()[0];
            riverFilter += "\"HY_DTM_ID\"= " + entry.getValue()[1];
        }

        roads = readShapeFile(new File(ROADS_FILE), roadsFilter, bounds, ROADS)
                .stream()
                .map(object -> ((Road) object))
                .collect(Collectors.toList());
        System.out.println("roads size = " + roads.size());

        List<Bridge> bridges = readShapeFile(new File(INFRASTRUCTURE_FILE), "\"SIF_VRSTE\" = 1102 AND \"ATR2\" = 1", bounds, BRIDGES)
                .stream()
                .map(object -> ((Bridge) object))
                .collect(Collectors.toList());
        System.out.println("bridge size = " + bridges.size());

        List<River> rivers = readShapeFile(new File(WATERS_FILE), riverFilter, bounds, RIVERS)
                .stream()
                .map(object -> ((River) object))
                .collect(Collectors.toList());
        System.out.println("rivers size = " + rivers.size());


        while(!mergeBridges(bridges)) {
            System.out.println("Repeat...");
        }
        System.out.println("bridges = " + bridges.size());

        int count = 0;
        for (Bridge bridge : bridges) {
            BoundingBox bridgeBounds = bridge.getSkirt();
            System.out.println("bridgeBounds = " + bridgeBounds.toString());

            //LASReader subread = reader.insideRectangle(bridgeBounds.getMinX(), bridgeBounds.getMinY(), bridgeBounds.getMaxX(), bridgeBounds.getMaxY());
            LASReader subread = reader.insideCircle(bridgeBounds.getMinX() + bridgeBounds.getWidth() / 2,
                    bridgeBounds.getMinY() + bridgeBounds.getHeight() / 2, Math.max(bridgeBounds.getWidth(), bridgeBounds.getHeight()) * 0.65);


            ArrayList<Vector3d> points = new ArrayList<>();
            for (LASPoint point : subread.getPoints()) {
                points.add(new Vector3d(point.getX(), point.getY(), point.getZ()));
            }


            // TODO: Get bridge width. Maybe even use meters.
            ArrayList<Vector3d> bridgePoints = new ArrayList<>();

            // Classify point on the bridge as bridge points.
            for (Vector3d vector3D : points) {
                Point point = geometryFactory.createPoint(new Coordinate(vector3D.x * scaleFactorX, vector3D.y * scaleFactorY));

                boolean isTypeBridge = false;
                for(MultiLineString bridgeComponent : bridge.getBridges()) {
                    LineString partOfBridge = (LineString) bridgeComponent.getGeometryN(0);
                    Polygon bridgeBox = getBridgeBoundingBox(partOfBridge, bridge.getWidth());

                    if(bridgeBox.contains(point)) {
                        isTypeBridge = true;
                        break;
                    }
                }

                if(isTypeBridge) {
                    bridgePoints.add(vector3D);
                }
                else {
                    terrainTree = terrainTree.add(vector3D, PointDouble.create(vector3D.x, vector3D.y));
                }
            }

            System.out.println("bridgePoints count = " + bridgePoints.size());

            ArrayList<Vector3d> generatedPoints;

            generatedPoints = restoreUnderTheBridge(bridge, rivers);
            // TODO: Get real Z-value for bottom part of bridge.
            generatedPoints.addAll(restoreBridge(bridge, bridgePoints.get(0).getZ()));

            points.addAll(generatedPoints);
            //points.removeAll(bridgePoints);

            write(points, count++);
            //System.exit(0);
        }
    }

    private static ArrayList<Vector3d> restoreBridge(Bridge bridge, double bridgeZ) {
        ArrayList<Vector3d> generatedPoints = new ArrayList<>();
        for(MultiLineString bridgeComponent : bridge.getBridges()) {
            System.out.println("geometries = " + bridgeComponent.getNumGeometries());
            for (int i = 0; i < bridgeComponent.getNumGeometries(); i++) {
                LineString partOfBridge = (LineString) bridgeComponent.getGeometryN(i);

                Vector3d A = new Vector3d(partOfBridge.getStartPoint().getX(), partOfBridge.getStartPoint().getY(), 0);
                Vector3d B = new Vector3d(partOfBridge.getEndPoint().getX(), partOfBridge.getEndPoint().getY(), 0);

                Vector3d directionVector = new Vector3d(B.getX() - A.getX(), B.getY() - A.getY(), 0);
                directionVector.normalize();
                Vector3d normal = getNormal(A, B);

                Vector3d realVector = normal;
                double realDistance = bridge.getWidth();
                double BRIDGE_STEP = 1;

                Polygon polygon = getBridgeBoundingBox(partOfBridge, bridge.getWidth());

                double bridgeLength = new Vector3d(B.getX() - A.getX(), B.getY() - A.getY(), 0).length();
                double STEP = 1;

                ArrayList<Vector3d> neighbours = new ArrayList<>();
                for(Coordinate coordinate : polygon.getCoordinates()) {
                    neighbours.add(new Vector3d(coordinate.x, coordinate.y, bridgeZ-200));
                }

                System.out.println("bridge length = " + bridgeLength);
                for (double j = 0; j < bridgeLength; j += BRIDGE_STEP) {
                    Vector3d bridgePoint = new Vector3d(directionVector);
                    bridgePoint.scale(j);
                    bridgePoint.add(A);

                    for(double k = realDistance; k > 0; k -= STEP) {
                        traverseBridge(generatedPoints, bridgePoint, realVector, k, realDistance, neighbours);
                        traverseBridge(generatedPoints, bridgePoint, realVector, -k, realDistance, neighbours);
                    }
                    bridgePoint.x = bridgePoint.x * (1/scaleFactorX);
                    bridgePoint.y = bridgePoint.y * (1/scaleFactorY);

                    Vector3d centerPoint = new Vector3d(bridgePoint.x, bridgePoint.y, interpolateZ(bridgePoint, realDistance, neighbours));
                    bridgeTree = bridgeTree.add(centerPoint, PointDouble.create(centerPoint.x, centerPoint.y));
                    generatedPoints.add(centerPoint);
                }
            }
        }
        return generatedPoints;
    }

    private static ArrayList<Vector3d> restoreUnderTheBridge(Bridge bridge, List<River> rivers) {
        ArrayList<Vector3d> generatedPoints = new ArrayList<>();
        for(MultiLineString bridgeComponent : bridge.getBridges()) {
            System.out.println("geometries = " + bridgeComponent.getNumGeometries());
            for (int i = 0; i < bridgeComponent.getNumGeometries(); i++) {
                LineString partOfBridge = (LineString) bridgeComponent.getGeometryN(i);

                Vector3d A = new Vector3d(partOfBridge.getStartPoint().getX(), partOfBridge.getStartPoint().getY(), 0);
                Vector3d B = new Vector3d(partOfBridge.getEndPoint().getX(), partOfBridge.getEndPoint().getY(), 0);

                Vector3d directionVector = new Vector3d(B.getX() - A.getX(), B.getY() - A.getY(), 0);
                directionVector.normalize();
                Vector3d normal = getNormal(A, B);


                Vector3d realVector = getDirectionVector(bridge, partOfBridge, rivers, normal);
                double theta = realVector.angle(normal);
                double realDistance = Math.sqrt(Math.pow(bridge.getWidth(), 2) + Math.pow(Math.tan(theta) * bridge.getWidth(), 2));
                double BRIDGE_STEP = 1;

                double delta = Math.sqrt(Math.pow(realDistance, 2) - Math.pow(bridge.getWidth(), 2));
                A = addVectors(A, scaleVector(directionVector, -delta));
                B = addVectors(B, scaleVector(directionVector, delta));

                double bridgeLength = new Vector3d(B.getX() - A.getX(), B.getY() - A.getY(), 0).length();

                System.out.println("bridge length = " + bridgeLength);
                for (double j = 0; j < bridgeLength; j += BRIDGE_STEP) {
                    System.out.println("done = " + (j / bridgeLength));
                    Vector3d bridgePoint = new Vector3d(directionVector);
                    bridgePoint.scale(j);
                    bridgePoint.add(A);

                    double STEP = 1;

                    ArrayList<Vector3d> neighbours = new ArrayList<>();
                    neighbours.addAll(getNeighbours(bridgePoint, realVector, realDistance, STEP*150, 1));
                    neighbours.addAll(getNeighbours(bridgePoint, realVector, realDistance, STEP*150, -1));

                    /*for(Vector3d vector3d : neighbours) {
                        vector3d.z = 27000;
                        generatedPoints.add(vector3d);
                    }*/

                    BRIDGE_STEP = STEP;

                    System.out.println("STEP = " + STEP);

                    for(double k = realDistance; k > 0; k -= STEP) {
                        traverseBridge(generatedPoints, bridgePoint, realVector, k, realDistance, neighbours);
                        traverseBridge(generatedPoints, bridgePoint, realVector, -k, realDistance, neighbours);
                    }

                    bridgePoint.x = bridgePoint.x * (1/scaleFactorX);
                    bridgePoint.y = bridgePoint.y * (1/scaleFactorY);
                    generatedPoints.add(new Vector3d(bridgePoint.x, bridgePoint.y, interpolateZ(bridgePoint, realDistance, neighbours)));
                }
            }
        }
        return generatedPoints;
    }

    private static List<Vector3d> getNeighbours(Vector3d bridgePoint, Vector3d realVector, double realDistance, double STEP, int negative) {
        List<Vector3d> neighbours = new ArrayList<>();
        double searchStep = 1.1f;
        while(neighbours.size() < 2) {
            Vector3d afterEnd = new Vector3d(bridgePoint);
            Vector3d tempDirection = new Vector3d(realVector);
            tempDirection.scale(realDistance * searchStep * negative);
            afterEnd.add(tempDirection);

            afterEnd.x = afterEnd.x * (1/scaleFactorX);
            afterEnd.y = afterEnd.y * (1/scaleFactorY);

            neighbours = density(afterEnd, STEP, Integer.MAX_VALUE);
            searchStep += 0.1;
            if(searchStep > 1.8f)
                break;
        }

        Collections.sort(neighbours, new Comparator<Vector3d>() {
            @Override
            public int compare(Vector3d o1, Vector3d o2) {
                /*System.out.println(o1.value().z);
                System.out.println(o2.value().z);*/
                if(o1.z < o2.z) {
                    return -1;
                }
                else if(o1.z > o2.z) {
                    return 1;
                }
                else
                    return 0;
            }
        });
        if(neighbours.size() > 10) {
            // Sort by Z value.
            neighbours = neighbours.subList(0, 10);
        }
        double deltaMax = 0;
        for(int i = 0; i < neighbours.size() - 1; i++) {
            if(Math.abs(neighbours.get(i).z - neighbours.get(i+1).z) > deltaMax) {
                deltaMax = Math.abs(neighbours.get(i).z - neighbours.get(i+1).z);
            }
            if(deltaMax > 100) {
                neighbours = neighbours.subList(0, i);
                break;
            }
        }


        //System.err.println("MAX DELTA = " + deltaMax);

        return neighbours;
    }

    private static Vector3d getDirectionVector(Bridge bridge, LineString lineString, List<River> others, Vector3d fallBack) {
        long targetId = dataIds.get(bridge.getId())[1];

        for(River river : others) {
            if(river.getId() != targetId)
                continue;

            LineString partOfBridge = (LineString) river.getRiver().getGeometryN(0);
            //LineString partOfBridge = (LineString) multiLineString.getGeometryN(i);
            System.out.println("partOfBridge ? " + partOfBridge.getBoundary().toString());
            System.out.println("lineString ? " + lineString.getBoundary().toString());
            System.err.println("INTERSECTS = " + partOfBridge.intersects(lineString));
            Vector3d A = new Vector3d(partOfBridge.getStartPoint().getX(), partOfBridge.getStartPoint().getY(), 0);
            Vector3d B = new Vector3d(partOfBridge.getEndPoint().getX(), partOfBridge.getEndPoint().getY(), 0);
            Vector3d directionVector = new Vector3d(B.getX() - A.getX(), B.getY() - A.getY(), 0);
            directionVector.normalize();
            System.out.println("FOUNDDDDD!!! dir = " + directionVector.toString());
            System.out.println("FOUNDDDDD!!! normal = " + fallBack.toString());

            return directionVector;

        }
        System.out.println("NOT FOUNDD!!!");

        return fallBack;
    }

    private static Vector3d getNormal(Vector3d A, Vector3d B) {
        Vector3d directionVector = new Vector3d(B.getX() - A.getX(), B.getY() - A.getY(), 0);
        directionVector.normalize();
        Vector3d normal = new Vector3d();
        normal.cross(directionVector, new Vector3d(0, 0, 1));
        normal.normalize();
        return normal;
    }

    private static Polygon getBridgeBoundingBox(LineString partOfBridge, double bridgeWidth) {
        Vector3d A = new Vector3d(partOfBridge.getStartPoint().getX(), partOfBridge.getStartPoint().getY(), 0);
        Vector3d B = new Vector3d(partOfBridge.getEndPoint().getX(), partOfBridge.getEndPoint().getY(), 0);

        Vector3d normal = getNormal(A, B);
        normal.scale(bridgeWidth);

        Vector3d negNormal = new Vector3d(normal);
        negNormal.scale(-1);

        Vector3d vector1 = addVectors(A, normal);
        Vector3d vector2 = addVectors(A, negNormal);
        Vector3d vector3 = addVectors(B, negNormal);
        Vector3d vector4 = addVectors(B, normal);

        Point point1 = geometryFactory.createPoint(new Coordinate(vector1.x, vector1.y));
        Point point2 = geometryFactory.createPoint(new Coordinate(vector2.x, vector2.y));
        Point point3 = geometryFactory.createPoint(new Coordinate(vector3.x, vector3.y));
        Point point4 = geometryFactory.createPoint(new Coordinate(vector4.x, vector4.y));

        return geometryFactory.createPolygon(new Coordinate[] {point1.getCoordinate(),
                point2.getCoordinate(),
                point3.getCoordinate(),
                point4.getCoordinate(),
        point1.getCoordinate()});
    }

    private static Vector3d addVectors(Vector3d vector1, Vector3d vector2) {
        Vector3d result = new Vector3d(vector1);
        result.add(vector2);
        return result;
    }

    private static Vector3d scaleVector(Vector3d vector1, double scaleFactor) {
        Vector3d result = new Vector3d(vector1);
        result.scale(scaleFactor);
        return result;
    }

    private static void traverseBridge(ArrayList<Vector3d> generatedPoints,
                                       Vector3d pointOnBridge,
                                       Vector3d direction,
                                       double k,
                                       double realDistance,
                                       ArrayList<Vector3d> neighbours) {
        Vector3d bridgePoint = new Vector3d(pointOnBridge);

        Vector3d tempDirection = new Vector3d(direction);
        tempDirection.scale(k);
        bridgePoint.add(tempDirection);

        bridgePoint.x = bridgePoint.x * (1/scaleFactorX);
        bridgePoint.y = bridgePoint.y * (1/scaleFactorY);

        /*if(density(bridgePoint, 10) > 1)
            break;*/
        /*if(density(bridgePoint, generatedPoints, 20) > 0)
            return;*/

        Vector3d result = new Vector3d(bridgePoint.x, bridgePoint.y, interpolateZ(bridgePoint, realDistance, neighbours));
        bridgeTree = bridgeTree.add(result, PointDouble.create(result.x, result.y));
        generatedPoints.add(result);
        //System.out.println("result = " + result.toString());
        //terrainTree = terrainTree.add(result, PointDouble.create(result.x, result.y));
    }

    /*private static int density(Vector3d point, ArrayList<Vector3d> bridgePoints, int radius) {
        int count = 0;
        for(Vector3d bridgePoint : bridgePoints) {
            if(distance(bridgePoint, point) < radius)
                count++;
        }
        return count;
    }*/

    private static ArrayList<Vector3d> density(Vector3d point, double radius, int maxCount) {
        Iterator<Entry<Vector3d, Geometry>> iterator = terrainTree.nearest(Geometries.point(point.x, point.y), radius, maxCount).iterator();
        ArrayList<Vector3d> result = new ArrayList<>();
        while(iterator.hasNext()) {
            result.add(iterator.next().value());
        }
        return result;
    }

    // Merge overlapping bridges
    private static boolean mergeBridges(List<Bridge> bridges) {
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

    private static double interpolateZ(Vector3d bridgePoint, double realDistance, ArrayList<Vector3d> neighbours) {
        List<Vector3d> comparators = new ArrayList<>();

        comparators.addAll(neighbours);

        boolean failsafe = false;
        if (comparators.size() == 0) {

            //System.err.println("ERROR NO FOUND!");
            List<Entry<Vector3d, Geometry>> entries = Iterables.toList(bridgeTree.nearest(Geometries.point(bridgePoint.x, bridgePoint.y), realDistance*100, 10));
            for(Entry<Vector3d, Geometry> entry : entries) {
                comparators.add(entry.value());
            }
            failsafe = true;
        }


        double values = 0;
        double weightsSum = 0;

        // Sort by Z value.
        Collections.sort(comparators, new Comparator<Vector3d>() {
            @Override
            public int compare(Vector3d o1, Vector3d o2) {
                /*System.out.println(o1.value().z);
                System.out.println(o2.value().z);*/
                if(o1.z < o2.z) {
                    return -1;
                }
                else if(o1.z > o2.z) {
                    return 1;
                }
                else
                    return 0;
            }
        });

       // System.out.println(Arrays.toString(comparators.toArray()));
        /*if (comparators.si) {
            comparators = comparators.subList(0, 10);
        }*/

        //System.out.println("size = " + entries.size());
        for (Vector3d entry : comparators) {
            double distance = distance(entry, bridgePoint);
            if(distance == 0)
                continue;

            double newWeight = 1 / distance;

            values += newWeight * entry.z;
            weightsSum += newWeight;
        }
        //System.out.println("values = " + values);
        //System.out.println("weightsum = " + weightsSum);

        double z =  values / weightsSum;
        //System.out.println("Z = " + z);
        return z;
    }

    private static double distance(Vector3d a, Vector3d b) {
        double distance = Math.sqrt(Math.pow(a.x - b.x, 2) + Math.pow(a.y - b.y, 2));
        //System.out.println("distance = " + distance);
        return distance;
    }

    private static void write(ArrayList<Vector3d> points, int number) {
        try (Writer writer = new BufferedWriter(new OutputStreamWriter(
                new FileOutputStream(String.format("test%d.obj", number)), StandardCharsets.UTF_8))) {
            for(Vector3d lasPoint : points) {
                writer.write("v " + lasPoint.x + " " + lasPoint.y + " " + lasPoint.z + "\n");
            }
        }
        catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static ArrayList<Object> readShapeFile(File file, String filter, Envelope2D bounds, TYPE type) {
        ArrayList<Object> result = new ArrayList<>();

        try {
            Map<String, String> connect = new HashMap();
            connect.put("url", file.toURI().toString());

            DataStore dataStore = DataStoreFinder.getDataStore(connect);
            String[] typeNames = dataStore.getTypeNames();
            String typeName = typeNames[0];

            System.out.println("Reading content " + typeName);

            FeatureSource featureSource = dataStore.getFeatureSource(typeName);
            FeatureCollection collection = featureSource.getFeatures();
            if(filter != null)
                collection = collection.subCollection(CQL.toFilter(filter));

            FeatureIterator iterator = collection.features();

            try {
                while (iterator.hasNext()) {
                    Feature feature = iterator.next();
                    GeometryAttribute sourceGeometry = feature.getDefaultGeometryProperty();

                    CoordinateReferenceSystem coordinateReferenceSystem = sourceGeometry.getBounds().getCoordinateReferenceSystem();
                    BoundingBox bridgeBox = sourceGeometry.getBounds();
                    BoundingBox lasBox = bounds.toBounds(coordinateReferenceSystem);

                    if(bridgeBox.intersects(lasBox)) {

                        if(type == BRIDGES) {
                            System.out.println("FOUND!");
                            Bridge bridge = new Bridge((long)feature.getProperties("ID").iterator().next().getValue());

                            MultiLineString targetBridge = (MultiLineString) sourceGeometry.getValue();

                            bridge.addBridge(targetBridge);
                            bridge.setSkirt(bridgeBox);
                            long roadId = dataIds.get(bridge.getId())[0];

                            for(Object object : roads) {
                                Road road = (Road) object;
                                if(road.getId() == roadId) {
                                    System.err.println("SUCESSS!! " + road.getWidth());
                                    bridge.setWidth(road.getWidth());
                                }
                            }
                            result.add(bridge);
                        }
                        else if(type == ROADS) {
                            Road road = new Road((long)feature.getProperties("TN_DTM_ID").iterator().next().getValue());
                            if(feature.getProperties("SIRVOZ").iterator().hasNext()) {
                                double roadWidth = (double) feature.getProperties("SIRVOZ").iterator().next().getValue();
                                road.setWidth(roadWidth);
                            }
                            MultiLineString targetBridge = (MultiLineString) sourceGeometry.getValue();

                            road.setRoad(targetBridge);
                            result.add(road);
                        }
                        else if(type == RIVERS) {
                            River river = new River((long)feature.getProperties("HY_DTM_ID").iterator().next().getValue());
                            MultiLineString targetBridge = (MultiLineString) sourceGeometry.getValue();
                            river.setRiver(targetBridge);
                            result.add(river);
                        }
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

    enum TYPE {
        BRIDGES,
        ROADS,
        RIVERS
    }


}
