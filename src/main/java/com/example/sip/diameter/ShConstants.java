package com.example.sip.diameter;

/**
 * Diameter / 3GPP Sh constants used by the gateway.
 *
 * <p>Note: Sh User-Data-Request command code is {@code 306} (3GPP TS 29.329 / jDiameter).
 * Profile-Update-Request is {@code 307}.
 */
public final class ShConstants {

    public static final long VENDOR_3GPP = 10415L;
    public static final long APP_SH = 16777217L;

    public static final int CMD_UDR = 306;
    public static final int CMD_PUR = 307;

    /** Base Diameter success. */
    public static final long RESULT_SUCCESS = 2001L;
    public static final long RESULT_UNABLE_TO_DELIVER = 3002L;
    public static final long RESULT_REALM_NOT_SERVED = 3003L;

    /** 3GPP Experimental-Result-Code: DIAMETER_ERROR_USER_UNKNOWN. */
    public static final long RESULT_USER_UNKNOWN = 5001L;

    public static final int AVP_SESSION_ID = 263;
    public static final int AVP_VENDOR_SPECIFIC_APPLICATION_ID = 260;
    public static final int AVP_AUTH_SESSION_STATE = 277;
    public static final int AVP_ORIGIN_HOST = 264;
    public static final int AVP_ORIGIN_REALM = 296;
    public static final int AVP_DESTINATION_REALM = 283;
    public static final int AVP_DESTINATION_HOST = 293;
    public static final int AVP_VENDOR_ID = 266;
    public static final int AVP_AUTH_APPLICATION_ID = 258;
    public static final int AVP_RESULT_CODE = 268;
    public static final int AVP_EXPERIMENTAL_RESULT = 297;
    public static final int AVP_EXPERIMENTAL_RESULT_CODE = 298;

    /** 3GPP Sh AVPs. */
    public static final int AVP_USER_IDENTITY = 701;
    public static final int AVP_USER_DATA = 702;
    public static final int AVP_DATA_REFERENCE = 703;
    public static final int AVP_SERVICE_INDICATION = 704;
    public static final int AVP_MSISDN = 701; // within User-Identity grouped; tel URI often in Public-Identity 601
    public static final int AVP_PUBLIC_IDENTITY = 601;

    public static final int DATA_REFERENCE_REPOSITORY_DATA = 0;
    public static final int AUTH_SESSION_STATE_NO_STATE_MAINTAINED = 1;

    private ShConstants() {
    }
}
