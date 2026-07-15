package com.inferpkg.core;

public class PackageCandidate {

    private final String packageName;
    private final int classCount;
    private final double percentage;

    public PackageCandidate(String packageName, int classCount, double percentage) {
        this.packageName = packageName;
        this.classCount = classCount;
        this.percentage = percentage;
    }

    public String getPackageName() {
        return packageName;
    }

    public int getClassCount() {
        return classCount;
    }

    public double getPercentage() {
        return percentage;
    }
}
