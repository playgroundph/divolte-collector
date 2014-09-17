package io.divolte.server;

import static io.divolte.server.BaseEventHandler.*;
import io.divolte.server.ip2geo.LookupService;
import io.undertow.server.HttpServerExchange;
import io.undertow.server.handlers.Cookie;
import io.undertow.util.AttachmentKey;
import io.undertow.util.Headers;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import javax.annotation.ParametersAreNonnullByDefault;
import javax.annotation.concurrent.NotThreadSafe;

import net.sf.uadetector.OperatingSystem;
import net.sf.uadetector.ReadableUserAgent;
import net.sf.uadetector.UserAgentStringParser;
import net.sf.uadetector.service.UADetectorServiceFactory;

import org.apache.avro.Schema;
import org.apache.avro.Schema.Field;
import org.apache.avro.Schema.Type;
import org.apache.avro.generic.GenericRecord;
import org.apache.avro.generic.GenericRecordBuilder;
import org.apache.http.client.utils.URLEncodedUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.primitives.Doubles;
import com.google.common.primitives.Floats;
import com.google.common.primitives.Ints;
import com.google.common.primitives.Longs;
import com.maxmind.geoip2.model.CityResponse;
import com.maxmind.geoip2.record.City;
import com.maxmind.geoip2.record.Continent;
import com.maxmind.geoip2.record.Country;
import com.maxmind.geoip2.record.Location;
import com.maxmind.geoip2.record.Postal;
import com.maxmind.geoip2.record.Subdivision;
import com.maxmind.geoip2.record.Traits;
import com.typesafe.config.Config;
import com.typesafe.config.ConfigObject;
import com.typesafe.config.ConfigValue;
import com.typesafe.config.ConfigValueType;

@ParametersAreNonnullByDefault
@NotThreadSafe
final class RecordMapper {
    private final static Logger logger = LoggerFactory.getLogger(RecordMapper.class);

    private final String sessionIdCookie;

    private final Schema schema;
    private final Map<String, Pattern> regexes;
    private final List<FieldSetter> setters;

    private final LoadingCache<String,ReadableUserAgent> uaLookupCache;
    private final Optional<LookupService> geoipService;

    public RecordMapper(final Schema schema,
                        final Config schemaConfig,
                        final Config globalConfig,
                        final Optional<LookupService> geoipService) {
        Objects.requireNonNull(schemaConfig);
        Objects.requireNonNull(globalConfig);

        this.sessionIdCookie = globalConfig.getString("divolte.tracking.session_cookie");

        final int version = schemaConfig.getInt("divolte.tracking.schema_mapping.version");
        checkVersion(version);

        this.regexes = regexMapFromConfig(schemaConfig);
        this.setters = setterListFromConfig(schema, schemaConfig);

        this.schema = Objects.requireNonNull(schema);

        final UserAgentStringParser parser = parserBasedOnTypeConfig(globalConfig.getString("divolte.tracking.ua_parser.type"));
        this.uaLookupCache = sizeBoundCacheFromLoadingFunction(parser::parse, globalConfig.getInt("divolte.tracking.ua_parser.cache_size"));

        this.geoipService = Objects.requireNonNull(geoipService);

        logger.info("User agent parser data version: {}", parser.getDataVersion());
    }

    private static UserAgentStringParser parserBasedOnTypeConfig(String type) {
        switch (type) {
        case "caching_and_updating":
            logger.info("Using caching and updating user agent parser.");
            return UADetectorServiceFactory.getCachingAndUpdatingParser();
        case "online_updating":
            logger.info("Using online updating user agent parser.");
            return UADetectorServiceFactory.getOnlineUpdatingParser();
        case "non_updating":
            logger.info("Using non-updating (resource module based) user agent parser.");
            return UADetectorServiceFactory.getResourceModuleParser();
        default:
            throw new RuntimeException("Invalid user agent parser type. Valid values are: caching_and_updating, online_updating, non_updating.");
        }
    }

    private static FieldSetter fieldSetterFromConfig(final Schema schema, final Entry<String, ConfigValue> entry) {
        final String targetFieldName = entry.getKey();
        final Schema.Field field = schema.getField(targetFieldName);
        if (null == field) {
            throw new SchemaMappingException("Schema missing mapped field: %s", targetFieldName);
        }
        final FieldSupplier<?> fieldSupplier = fieldSupplierFromConfig(entry, schema);
        return (b, e, c) -> fieldSupplier.get(c).ifPresent((v) -> b.set(field, v));
    }

    private static FieldSupplier<?> fieldSupplierFromConfig(final Entry<String, ConfigValue> entry, Schema schema) {
        final ConfigValue value = entry.getValue();
        final String targetFieldName = entry.getKey();

        switch (value.valueType()) {
        case STRING:
            return simpleFieldSupplier((String) value.unwrapped());
        case OBJECT:
            final Config subConfig = ((ConfigObject) value).toConfig();
            return OptionalConfig.of(subConfig::getString, "type")
                                 .map((type) -> complexFieldSupplierFromConfig(targetFieldName, type, subConfig, schema))
                                 .orElseThrow(() -> new SchemaMappingException("Missing type property on configuration for field %s.", targetFieldName));
        default:
            throw new SchemaMappingException("Schema mapping for fields can only be of type STRING or OBJECT. Found %s for field %s.", value.valueType(), targetFieldName);
        }
    }

    private static FieldSupplier<?> complexFieldSupplierFromConfig(final String targetFieldName, final String type, final Config config, final Schema schema) {
        switch (type) {
        case "cookie":
            final String cookieName = OptionalConfig.of(config::getString, "name")
                                                    .orElseThrow(() -> new SchemaMappingException("Cookie mapping for field %s requires a string 'name' property.", targetFieldName));

            final FieldSupplier<String> cookieFieldSupplier = (c) -> Optional.ofNullable(c.getServerExchange().getRequestCookies().get(cookieName))
                                  .map(Cookie::getValue);
            return castingSupplierForStringField(targetFieldName, cookieFieldSupplier, schema);
        case "event_parameter":
            final String parameterName = OptionalConfig.of(config::getString, "name")
                                                       .orElseThrow(() -> new SchemaMappingException("Event parameter mapping for field %s requires a string 'name' property.", targetFieldName));
            final String queryParameterName = EVENT_TYPE_QUERY_PARAM + "." + parameterName;
            FieldSupplier<String> eventParameterFieldSupplier = (FieldSupplier<String>) (c) -> c.getQueryParameter(queryParameterName);
            return castingSupplierForStringField(targetFieldName, eventParameterFieldSupplier, schema);
        case "regex_group":
            final FieldSupplier<String> regexGroupFieldSupplier = regexGroupFieldSupplier(config, targetFieldName);
            return castingSupplierForStringField(targetFieldName, regexGroupFieldSupplier, schema);
        case "regex_name":
            return regexNameFieldSupplier(config, targetFieldName);
        case "query_param":
            final FieldSupplier<String> queryParamFieldSupplier = queryParamFieldSupplier(config, targetFieldName);
            return castingSupplierForStringField(targetFieldName, queryParamFieldSupplier, schema);
        default:
            throw new SchemaMappingException("Unknown mapping type: %s for field %s.", type, targetFieldName);
        }
    }

    private static FieldSupplier<?> castingSupplierForStringField(final String targetFieldName, final FieldSupplier<String> original, final Schema schema) {
        Field avroField = schema.getField(targetFieldName);
        final Type originalType = avroField.schema().getType();
        final Type type;
        if (originalType == Type.UNION) {
            List<Schema> unionTypes = avroField.schema().getTypes();

            final SchemaMappingException wrongUnionException = new SchemaMappingException("Field %s is a Avro UNION type which is not of the form [\"null\", \"<other primitive>\"], "
                    + "which is not supported. UNION is only supported to create a nullable field with possible null default value.", targetFieldName);

            if (unionTypes.size() != 2) {
                throw wrongUnionException;
            }

            type = unionTypes
                .stream()
                .filter((t) -> t.getType() != Type.NULL)
                .findFirst()
                .map(Schema::getType)
                .orElseThrow(() -> wrongUnionException);
        } else {
            type = originalType;
        }

        switch(type) {
        case STRING:
            return original;
        case BOOLEAN:
            // Boolean.valueOf(String) returns true for "true" and false for anything else
            // this means we always set the field if there is some kind of value
            return (c) -> original.get(c).map(Boolean::valueOf);
        case DOUBLE:
            return (c) -> original.get(c).map(Doubles::tryParse);
        case FLOAT:
            return (c) -> original.get(c).map(Floats::tryParse);
        case INT:
            return (c) -> original.get(c).map(Ints::tryParse);
        case LONG:
            return (c) -> original.get(c).map(Longs::tryParse);
        default:
            throw new SchemaMappingException("Schema mapping for field %s is a Avro field of type %s, which is not supported.", targetFieldName, type.toString());
        }
    }

    private static FieldSupplier<String> queryParamFieldSupplier(final Config config, final String targetFieldName) {
        final String paramName = OptionalConfig.of(config::getString, "name")
                                               .orElseThrow(() -> new SchemaMappingException("Query param mapping for field %s requires a 'name' property.", targetFieldName));
        final String sourceFieldName = OptionalConfig.of(config::getString, "field")
                .orElseThrow(() -> new SchemaMappingException("Query param mapping for field %s requires a string 'field' property.", targetFieldName));

        final FieldSupplier<String> fieldSupplier = queryParamFieldSupplierForName(sourceFieldName);
        return (c) -> fieldSupplier.get(c).map((value) -> c.queryStringParameterMap(sourceFieldName, value).get(paramName));
    }

    private static FieldSupplier<String> regexNameFieldSupplier(final Config config, final String targetFieldName) {
        final List<String> regexNames = OptionalConfig.of(config::getStringList, "regexes")
                                                      .orElseThrow(() -> new SchemaMappingException("Regex name mapping for field %s requires a array 'regexes' property.", targetFieldName));
        final String sourceFieldName = OptionalConfig.of(config::getString, "field")
                                               .orElseThrow(() -> new SchemaMappingException("Regex name mapping for field %s requires a string 'field' property.", targetFieldName));
        final FieldSupplier<String> fieldSupplier = regexFieldSupplierForName(sourceFieldName);

        return (c) -> fieldSupplier.get(c)
                                   .flatMap((s) -> regexNames.stream().filter((rn) -> c.matcher(rn, sourceFieldName, s).matches())
                                                                      .findFirst());
    }

    private static FieldSupplier<String> regexGroupFieldSupplier(final Config config, final String targetFieldName) {
        final String regexName = OptionalConfig.of(config::getString, "regex").orElseThrow(() -> new SchemaMappingException("Regex group mapping for field %s requires a string 'regex' property.", targetFieldName));
        final String sourceFieldName = OptionalConfig.of(config::getString, "field").orElseThrow(() -> new SchemaMappingException("Regex group mapping for field %s requires a string 'field' property.", targetFieldName));
        final String groupName = OptionalConfig.of(config::getString, "group").orElseThrow(() -> new SchemaMappingException("Regex group mapping for field %s requires a string 'group' property.", targetFieldName));
        final FieldSupplier<String> fieldSupplier = regexFieldSupplierForName(sourceFieldName);

        return (c) -> fieldSupplier.get(c)
                                 .flatMap((s) -> groupFromMatcher(c.matcher(regexName, sourceFieldName, s), groupName));
    }

    private static final FieldSupplier<String> REMOTE_HOST_FIELD_PRODUCER =
            (c) -> Optional.ofNullable(c.getServerExchange().getSourceAddress())
                           .map(InetSocketAddress::getHostString);
    private static final FieldSupplier<String> REFERER_FIELD_PRODUCER = (c) -> c.getQueryParameter(REFERER_QUERY_PARAM);
    private static final FieldSupplier<String> LOCATION_FIELD_PRODUCER = (c) -> c.getQueryParameter(LOCATION_QUERY_PARAM);
    private static final FieldSupplier<String> USERAGENT_FIELD_PRODUCER = (c) -> c.userAgent.get();

    private static FieldSupplier<String> regexFieldSupplierForName(final String name) {
        switch (name) {
        case "userAgent":
            return USERAGENT_FIELD_PRODUCER;
        case "remoteHost":
            return REMOTE_HOST_FIELD_PRODUCER;
        case "referer":
            return REFERER_FIELD_PRODUCER;
        case "location":
            return LOCATION_FIELD_PRODUCER;
        default:
            throw new SchemaMappingException("Only userAgent, remoteHost, referer and location fields can be used for regex matchers. Found %s.", name);
        }
    }

    private static FieldSupplier<String> queryParamFieldSupplierForName(final String name) {
        switch (name) {
        case "referer":
            return REFERER_FIELD_PRODUCER;
        case "location":
            return LOCATION_FIELD_PRODUCER;
        default:
            throw new SchemaMappingException("Only referer and location fields can be used for query string matchers. Found %s.", name);
        }
    }

    private static FieldSupplier<?> simpleFieldSupplier(final String sourceFieldName) {
        switch (sourceFieldName) {
        case "eventType":
            return (FieldSupplier<String>) (c) -> c.getQueryParameter(EVENT_TYPE_QUERY_PARAM);
        case "firstInSession":
            return (c) -> Optional.of(c.isFirstInSession());
        case "geoCityId":
            return (c) -> c.getCity().map(City::getGeoNameId);
        case "geoCityName":
            return (c) -> c.getCity().map(City::getName);
        case "geoContinentCode":
            return (c) -> c.getContinent().map(Continent::getCode);
        case "geoContinentId":
            return (c) -> c.getContinent().map(Continent::getGeoNameId);
        case "geoContinentName":
            return (c) -> c.getContinent().map(Continent::getName);
        case "geoCountryCode":
            return (c) -> c.getCountry().map(Country::getIsoCode);
        case "geoCountryId":
            return (c) -> c.getCountry().map(Country::getGeoNameId);
        case "geoCountryName":
            return (c) -> c.getCountry().map(Country::getName);
        case "geoLatitude":
            return (c) -> c.getLocation().map(Location::getLatitude);
        case "geoLongitude":
            return (c) -> c.getLocation().map(Location::getLongitude);
        case "geoMetroCode":
            return (c) -> c.getLocation().map(Location::getMetroCode);
        case "geoTimeZone":
            return (c) -> c.getLocation().map(Location::getTimeZone);
        case "geoMostSpecificSubdivisionCode":
            return (c) -> c.getMostSpecificSubdivision().map(Subdivision::getIsoCode);
        case "geoMostSpecificSubdivisionId":
            return (c) -> c.getMostSpecificSubdivision().map(Subdivision::getGeoNameId);
        case "geoMostSpecificSubdivisionName":
            return (c) -> c.getMostSpecificSubdivision().map(Subdivision::getName);
        case "geoPostalCode":
            return (c) -> c.geoLookup.get().map((r) -> r.getPostal()).map(Postal::getCode);
        case "geoRegisteredCountryCode":
            return (c) -> c.getRegisteredCountry().map(Country::getIsoCode);
        case "geoRegisteredCountryId":
            return (c) -> c.getRegisteredCountry().map(Country::getGeoNameId);
        case "geoRegisteredCountryName":
            return (c) -> c.getRegisteredCountry().map(Country::getName);
        case "geoRepresentedCountryCode":
            return (c) -> c.getRepresentedCountry().map(Country::getIsoCode);
        case "geoRepresentedCountryId":
            return (c) -> c.getRepresentedCountry().map(Country::getGeoNameId);
        case "geoRepresentedCountryName":
            return (c) -> c.getRepresentedCountry().map(Country::getName);
        case "geoSubdivisionCodes":
            return (c) -> c.getSubdivisions().map((s) -> Lists.transform(s, Subdivision::getIsoCode));
        case "geoSubdivisionIds":
            return (c) -> c.getSubdivisions().map((s) -> Lists.transform(s, Subdivision::getGeoNameId));
        case "geoSubdivisionNames":
            return (c) -> c.getSubdivisions().map((s) -> Lists.transform(s, Subdivision::getName));
        case "geoAutonomousSystemNumber":
            return (c) -> c.getTraits().map(Traits::getAutonomousSystemNumber);
        case "geoAutonomousSystemOrganization":
            return (c) -> c.getTraits().map(Traits::getAutonomousSystemOrganization);
        case "geoDomain":
            return (c) -> c.getTraits().map(Traits::getDomain);
        case "geoIsp":
            return (c) -> c.getTraits().map(Traits::getIsp);
        case "geoOrganisation":
            return (c) -> c.getTraits().map(Traits::getOrganization);
        case "geoAnonymousProxy":
            return (c) -> c.getTraits().map(Traits::isAnonymousProxy);
        case "geoSatelliteProvider":
            return (c) -> c.getTraits().map(Traits::isSatelliteProvider);
        case "timestamp":
            return (FieldSupplier<Long>) (c) -> c.getAttachment(REQUEST_START_TIME_KEY);
        case "userAgent":
            return USERAGENT_FIELD_PRODUCER;
        case "userAgentName":
            return (c) -> c.getUserAgentLookup().map(ReadableUserAgent::getName);
        case "userAgentFamily":
            return (c) -> c.getUserAgentLookup().map((ua) -> ua.getFamily().getName());
        case "userAgentVendor":
            return (c) -> c.getUserAgentLookup().map(ReadableUserAgent::getProducer);
        case "userAgentType":
            return (c) -> c.getUserAgentLookup().map((ua) -> ua.getType().getName());
        case "userAgentVersion":
            return (c) -> c.getUserAgentLookup().map((ua) -> ua.getVersionNumber().toVersionString());
        case "userAgentDeviceCategory":
            return (c) -> c.getUserAgentLookup().map((ua) -> ua.getDeviceCategory().getName());
        case "userAgentOsFamily":
            return (c) -> c.getUserAgentOperatingSystem().map((os) -> os.getFamily().getName());
        case "userAgentOsVersion":
            return (c) -> c.getUserAgentOperatingSystem().map((os) -> os.getVersionNumber().toVersionString());
        case "userAgentOsVendor":
            return (c) -> c.getUserAgentOperatingSystem().map(OperatingSystem::getProducer);
        case "remoteHost":
            return REMOTE_HOST_FIELD_PRODUCER;
        case "referer":
            return REFERER_FIELD_PRODUCER;
        case "location":
            return LOCATION_FIELD_PRODUCER;
        case "viewportPixelWidth":
            return (c) -> c.getQueryParameter(VIEWPORT_PIXEL_WIDTH_QUERY_PARAM).map(Ints::tryParse);
        case "viewportPixelHeight":
            return (c) -> c.getQueryParameter(VIEWPORT_PIXEL_HEIGHT_QUERY_PARAM).map(Ints::tryParse);
        case "screenPixelWidth":
            return (c) -> c.getQueryParameter(SCREEN_PIXEL_WIDTH_QUERY_PARAM).map(Ints::tryParse);
        case "screenPixelHeight":
            return (c) -> c.getQueryParameter(SCREEN_PIXEL_HEIGHT_QUERY_PARAM).map(Ints::tryParse);
        case "partyId":
            return (c) -> c.getAttachment(PARTY_COOKIE_KEY).map((cv) -> cv.value);
        case "sessionId":
            return (c) -> c.getAttachment(SESSION_COOKIE_KEY).map((cv) -> cv.value);
        case "pageViewId":
            return (FieldSupplier<String>) (c) -> c.getAttachment(PAGE_VIEW_ID_KEY);
        default:
            throw new SchemaMappingException("Unknown field in schema mapping: %s", sourceFieldName);
        }
    }

    private static List<FieldSetter> setterListFromConfig(final Schema schema, final Config config) {
        if (!config.hasPath("divolte.tracking.schema_mapping.fields")) {
            throw new SchemaMappingException("Schema mapping configuration has no field mappings.");
        }

        final Set<Entry<String, ConfigValue>> entrySet = config.getConfig("divolte.tracking.schema_mapping.fields").root().entrySet();

        return entrySet.stream()
            .map((e) -> fieldSetterFromConfig(schema, e))
            .collect(Collectors.toCollection(() -> new ArrayList<>(entrySet.size())));
    }

    private static Map<String,Pattern> regexMapFromConfig(final Config config) {
        return config.hasPath("divolte.tracking.schema_mapping.regexes") ?
        config.getConfig("divolte.tracking.schema_mapping.regexes").root().entrySet().stream().collect(
                Collectors.<Entry<String,ConfigValue>, String, Pattern>toMap(
                Entry::getKey,
                (e) -> {
                    if (e.getValue().valueType() != ConfigValueType.STRING) {
                        throw new SchemaMappingException("Regexes config elements must be of type STRING. Found %s of type %s.", e.getKey(), e.getValue().valueType());
                    }
                    return Pattern.compile((String) e.getValue().unwrapped());
                })) : Collections.emptyMap();
    }

    private static void checkVersion(final int version) {
        if (version != 1) {
            throw new SchemaMappingException("Unsupported schema mapping configuration version: %d", version);
        }
    }

    private static Optional<String> groupFromMatcher(final Matcher matcher, final String group) {
        return matcher.matches() ? Optional.ofNullable(matcher.group(group)) : Optional.empty();
    }

    @FunctionalInterface
    private interface FieldSetter {
        void setFields(GenericRecordBuilder builder, HttpServerExchange exchange, Context context);
    }

    @FunctionalInterface
    private interface FieldSupplier<T> {
        Optional<T> get(Context context);
    }

    public GenericRecord newRecordFromExchange(final HttpServerExchange exchange) {
        final GenericRecordBuilder builder = new GenericRecordBuilder(schema);
        final Context context = new Context(exchange);
        setters.forEach((s) -> s.setFields(builder, exchange, context));
        return builder.build();
    }

    @ParametersAreNonnullByDefault
    public static class SchemaMappingException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public SchemaMappingException(String message) {
            super(message);
        }

        public SchemaMappingException(String message, Object... args) {
            this(String.format(message, args));
        }
    }

    private static <K,V> LoadingCache<K, V> sizeBoundCacheFromLoadingFunction(Function<K, V> loader, int size) {
        return CacheBuilder
                .newBuilder()
                .maximumSize(size)
                .initialCapacity(size)
                .build(new CacheLoader<K, V>() {
                    @Override
                    public V load(K key) throws Exception {
                        return loader.apply(key);
                    }
                });
    }

    private Optional<ReadableUserAgent> parseUserAgent(final String userAgentString) {
        try {
            return Optional.of(uaLookupCache.get(userAgentString));
        } catch (final ExecutionException e) {
            logger.debug("Failed to parse user agent string for: " + userAgentString);
            return Optional.empty();
        }
    }

    @ParametersAreNonnullByDefault
    private final class Context {

        // In general a regular expression is used against a single value, but it can be used more than once.
        private final Map<String, Matcher> matchers = Maps.newHashMapWithExpectedSize(regexes.size() * 2);
        private final Map<String, Map<String,String>> parsedQueryString = Maps.newHashMapWithExpectedSize(5);

        private final HttpServerExchange serverExchange;

        private final LazyReference<Optional<String>> userAgent;
        private final LazyReference<Optional<ReadableUserAgent>> userAgentLookup;
        private final LazyReference<Optional<CityResponse>> geoLookup;

        private Context(final HttpServerExchange serverExchange) {
            this.serverExchange = Objects.requireNonNull(serverExchange);
            this.userAgent = new LazyReference<>(() ->
                Optional.ofNullable(serverExchange.getRequestHeaders().getFirst(Headers.USER_AGENT)));
            this.userAgentLookup = new LazyReference<>(() -> userAgent.get().flatMap(RecordMapper.this::parseUserAgent));
            this.geoLookup = new LazyReference<>(() -> geoipService.flatMap((service) -> {
                final InetSocketAddress sourceAddress = serverExchange.getSourceAddress();
                final InetAddress ipAddress = null != sourceAddress ? sourceAddress.getAddress() : null;
                try {
                    return null != ipAddress ? service.lookup(ipAddress) : Optional.empty();
                } catch (final LookupService.ClosedServiceException e) {
                    return Optional.empty();
                }
            }));
        }

        public Map<String,String> queryStringParameterMap(final String field, final String value) {
            return parsedQueryString.computeIfAbsent(field, (ignored) -> {
                URI uri;
                try {
                    uri = new URI(value);
                } catch (URISyntaxException e) {
                    return Collections.emptyMap();
                }

                final String rawQuery = uri.getRawQuery();
                if (rawQuery == null) {
                    return Collections.emptyMap();
                } else {
                    return URLEncodedUtils
                            .parse(rawQuery, StandardCharsets.UTF_8)
                            .stream()
                            .collect(Collectors.toMap((nvp) -> nvp.getName(), (nvp) -> nvp.getValue()));
                }
            });
        }

        public Matcher matcher(final String regex, final String field, final String value) {
            final String key = regex + field;
            return matchers.computeIfAbsent(key, (ignored) -> regexes.get(regex).matcher(value));
        }

        public HttpServerExchange getServerExchange() {
            return serverExchange;
        }

        public Optional<String> getQueryParameter(final String parameterName) {
            return Optional.ofNullable(serverExchange.getQueryParameters().get(parameterName)).map(Deque::getFirst);
        }

        public <T> Optional<T> getAttachment(final AttachmentKey<T> key) {
            return Optional.of(serverExchange.getAttachment(key));
        }

        public boolean isFirstInSession() {
            return !serverExchange.getRequestCookies().containsKey(sessionIdCookie);
        }

        public Optional<ReadableUserAgent> getUserAgentLookup() {
            return userAgentLookup.get();
        }

        public Optional<OperatingSystem> getUserAgentOperatingSystem() {
            return getUserAgentLookup().map(ReadableUserAgent::getOperatingSystem);
        }

        public Optional<City> getCity() {
            return geoLookup.get().map((g) -> g.getCity());
        }

        public Optional<Continent> getContinent() {
            return geoLookup.get().map((g) -> g.getContinent());
        }

        public Optional<Country> getCountry() {
            return geoLookup.get().map((g) -> g.getCountry());
        }

        public Optional<Location> getLocation() {
            return geoLookup.get().map((g) -> g.getLocation());
        }

        public Optional<Subdivision> getMostSpecificSubdivision() {
            return geoLookup.get().map((g) -> g.getMostSpecificSubdivision());
        }

        public Optional<Country> getRegisteredCountry() {
            return geoLookup.get().map((g) -> g.getRegisteredCountry());
        }

        public Optional<Country> getRepresentedCountry() {
            return geoLookup.get().map((g) -> g.getRepresentedCountry());
        }

        public Optional<List<Subdivision>> getSubdivisions() {
            return geoLookup.get().map((g) -> g.getSubdivisions());
        }

        public Optional<Traits> getTraits() {
            return geoLookup.get().map((g) -> g.getTraits());
        }
    }
}
