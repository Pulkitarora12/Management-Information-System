package com.notes.notes.util;

public class FileStorageConfig {

    public static final String UPLOAD_ROOT =
            System.getenv().getOrDefault("MIS_UPLOAD_DIR", "C:/apps/MIS/uploads").replaceAll("/+$", "");

    public static final String IMAGE_UPLOAD_DIR = UPLOAD_ROOT + "/images/";
}