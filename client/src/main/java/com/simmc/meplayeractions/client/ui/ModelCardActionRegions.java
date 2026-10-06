package com.simmc.meplayeractions.client.ui;

/** Card corner actions must never fall through into browse/apply behavior. */
final class ModelCardActionRegions {
    enum Target { BROWSE, FAVORITE, UPLOAD }
    static Target target(double x,double y,int width,boolean uploads) {
        if(y>=0 && y<17) {
            if(x>=width-17 && x<width)return Target.FAVORITE;
            if(uploads && x>=0 && x<17)return Target.UPLOAD;
        }
        return Target.BROWSE;
    }
    private ModelCardActionRegions() { }
}
