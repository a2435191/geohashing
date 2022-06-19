package com.github.a2435191;

import org.jetbrains.annotations.NotNull;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.MathContext;
import java.math.RoundingMode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.HexFormat;
import java.util.Scanner;
import java.util.concurrent.CompletableFuture;

/**
 * Class to implement the algorithm described in <a href="https://xkcd.com/426/">the XKCD</a>.
 */
public final class Geohasher {


    /**
     * Search this many days back when scraping the Dow Jones Industrial Average data.
     */
    private static final int DAYS_SEARCH_WINDOW = 30; // if the dow isn't open for longer than a month we got bigger problems

    private static final String USER_AGENT = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/102.0.0.0 Safari/537.36";

    private static final DateFormat DASH_FORMAT = new SimpleDateFormat("yyyy-MM-dd");
    private static final DateFormat WSJ_FORMAT = new SimpleDateFormat("MM/dd/yyyy");

    private static final MessageDigest MD5;

    private static final BigDecimal HEX_DIVISION_FACTOR = BigDecimal.valueOf(16).pow(16);


    /**
     * URL formatter to get the DJIA data.
     */
    private static final String PRICES_URL_FORMAT = "https://www.wsj.com/market-data/quotes/index/DJIA/historical-prices/download?MOD_VIEW=page&num_rows=1&range_days=1&startDate=%s&endDate=%s";

    static {
        try {
            MD5 = MessageDigest.getInstance("MD5");
        } catch (NoSuchAlgorithmException e) {
            throw new Error(e);
        }
    }

    private final HttpClient client = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build();

    /**
     * Implementation of the original geohashing algorithm.
     * @param today The date to use in the calculation
     * @param dowOpening The most recent Dow opening
     * @param currentPos {@link Coordinate} representing the current position.
     *                                     Only needs to be accurate to the next lowest integer
     *                                     (i.e. 76.45 can be replaced by 75 and work fine).
     * @param precision Precision used in division calculations on {@link BigInteger}s.
     * @return The destination coordinates.
     */
    public static @NotNull Coordinate geoHash(
        @NotNull Calendar today,
        @NotNull BigDecimal dowOpening,
        @NotNull Coordinate currentPos,
        int precision) {
        if (dowOpening.scale() > 2) {
            throw new IllegalArgumentException("dowOpening should have scale at most 2!");
        }

        int missingZeroes = 2 - dowOpening.scale();

        String dowOpeningString = dowOpening.toPlainString() + "0".repeat(missingZeroes); // right pad
        String dateFormatted = DASH_FORMAT.format(today.getTime());

        String toBeHashed = dateFormatted + "-" + dowOpeningString;
        byte[] byteArrayHash = MD5.digest(toBeHashed.getBytes(StandardCharsets.UTF_8));

        String hexedHash = HexFormat.of().formatHex(byteArrayHash);
        assert (hexedHash.length() == 32);

        BigDecimal xDecimal = new BigDecimal(new BigInteger(hexedHash.substring(0, 16), 16))
            .divide(HEX_DIVISION_FACTOR, precision, RoundingMode.HALF_UP);

        xDecimal = xDecimal.setScale(xDecimal.precision(), RoundingMode.UNNECESSARY);

        BigDecimal yDecimal = new BigDecimal(new BigInteger(hexedHash.substring(16), 16))
            .divide(HEX_DIVISION_FACTOR, precision, RoundingMode.HALF_UP);
        yDecimal = yDecimal.setScale(yDecimal.precision(), RoundingMode.UNNECESSARY);

        BigDecimal currentXInt = new BigDecimal(currentPos.x().intValue());
        BigDecimal currentYInt = new BigDecimal(currentPos.y().intValue());

        BigDecimal x = currentXInt.add(xDecimal);
        BigDecimal y = currentYInt.add(yDecimal);

        return new Coordinate(x, y);


    }


    /**
     * Compute the geohash of the user's current position (provided). (There's no cross-platform, or even
     * straightforward MacOS-specific way to get the position without additional utilities and a lot more work,
     * so we leave it up to the end user to provide their coordinates, accurate to the next lowest integer.
     * See {@link Geohasher#geoHash(Calendar, BigDecimal, Coordinate, int)}.)
     *
     * @param args Provide latitude and longitude of current position via command line (two arguments)
     *             or through stdin after program start (zero arguments).
     */
    public static void main(String[] args) {
        final Calendar today = Calendar.getInstance();
        final CompletableFuture<BigDecimal> dowOpeningFuture = new Geohasher().mostRecentDowOpening(today);

        final BigDecimal x, y;
        if (args.length == 2) {
            x = new BigDecimal(args[0]);
            y = new BigDecimal(args[1]);
        } else if (args.length == 0) {
            final Scanner s = new Scanner(System.in);

            System.out.print("Enter latitude: ");
            x = new BigDecimal(s.nextLine());
            System.out.print("Enter longitude: ");
            y = new BigDecimal(s.nextLine());

            s.close();
        } else {
            throw new IllegalArgumentException(
                "args must have length 0 (enter lat, long via stdin) or 2 (provide lat, long)");
        }



        System.out.println("Today's date: " + DASH_FORMAT.format(today.getTime()));

        final BigDecimal dowOpening = dowOpeningFuture.join();
        System.out.println("Most recent Dow opening: " + dowOpening);


        Coordinate destination = geoHash(today, dowOpening, new Coordinate(x, y), 14);
        System.out.println("Your geohash:");
        System.out.println(destination.toSimpleString(5) + " " + destination.toGoogleMapsURL());

    }


    /**
     * Subroutine to compute the most recent DJIA opening price.
     * @param date Date to calculate. (If it's a Saturday, gets the data from Friday, except for holidays, etc.)
     * @return A {@link CompletableFuture<BigDecimal>} that scrapes the data over HTTPS.
     */
    public @NotNull CompletableFuture<@NotNull BigDecimal> mostRecentDowOpening(@NotNull Calendar date) {
        date = (Calendar) date.clone();
        final String formattedDateToday = WSJ_FORMAT.format(date.getTime());
        date.add(Calendar.DATE, -DAYS_SEARCH_WINDOW);
        final String formattedDateYesterday = WSJ_FORMAT.format(date.getTime());
        String pricesURL = String.format(PRICES_URL_FORMAT, formattedDateYesterday, formattedDateToday);

        HttpRequest request = HttpRequest.newBuilder()
            .GET()
            .header("User-Agent", USER_AGENT)
            .uri(URI.create(pricesURL))
            .build();
        CompletableFuture<@NotNull BigDecimal> outFuture = client.sendAsync(request, HttpResponse.BodyHandlers.ofString())
            .thenApply(resp -> {
                if (resp.statusCode() != 200) {
                    throw new RuntimeException("illegal status code: " + resp.statusCode());
                }
                String csvString = resp.body();
                // silly csv parsing
                BigDecimal opening = new BigDecimal(
                    csvString.split("\n")[1].split(", ")[1]
                );
                return opening;
            });


        return outFuture;
    }

    /**
     * Struct representing a coordinate.
     * @param x Latitude
     * @param y Longitude
     */
    public record Coordinate(@NotNull BigDecimal x, @NotNull BigDecimal y) {
        /**
         * Get a simple coordinate representation.
         * @param rounding How many digits to round the significant digits to.
         * @return Simple coordinate representation string.
         */
        public @NotNull String toSimpleString(int rounding) {
            MathContext mc = new MathContext(rounding, RoundingMode.HALF_UP);
            return "(" + x.round(mc) + ", " + y.round(mc) + ")";
        }

        public @NotNull String toGoogleMapsURL() {
            return String.format(
                "https://www.google.com/maps/place/%s,%s",
                x, y);
        }
    }
}