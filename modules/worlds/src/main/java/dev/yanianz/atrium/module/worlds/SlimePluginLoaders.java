package dev.yanianz.atrium.module.worlds;

import com.infernalsuite.asp.api.loaders.SlimeLoader;
import com.infernalsuite.asp.loaders.api.APILoader;
import com.infernalsuite.asp.loaders.mongo.MongoLoader;
import com.infernalsuite.asp.loaders.mysql.MysqlLoader;

/**
 * The mysql, mongodb and api loaders shipped by the ASPaperPlugin (visible through join-classpath). Hook class:
 * loaded only after the loader class was found, so a server without the ASPaperPlugin never touches it.
 */
final class SlimePluginLoaders {
    static final String MYSQL_CLASS = "com.infernalsuite.asp.loaders.mysql.MysqlLoader";
    static final String MONGO_CLASS = "com.infernalsuite.asp.loaders.mongo.MongoLoader";
    static final String API_CLASS = "com.infernalsuite.asp.loaders.api.APILoader";

    private SlimePluginLoaders() {}

    static SlimeLoader mysql(WorldsConfig.Mysql cfg) throws Exception {
        return new MysqlLoader(cfg.url, cfg.host, cfg.port, cfg.database, cfg.useSsl, cfg.username, cfg.password);
    }

    static SlimeLoader mongo(WorldsConfig.Mongo cfg) {
        return new MongoLoader(cfg.database, cfg.collection, blankToNull(cfg.username), blankToNull(cfg.password),
                blankToNull(cfg.authSource), blankToNull(cfg.host), cfg.port, blankToNull(cfg.uri));
    }

    static SlimeLoader api(WorldsConfig.Api cfg) {
        return new APILoader(cfg.url, cfg.username, cfg.token, cfg.ignoreSslCertificate);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
