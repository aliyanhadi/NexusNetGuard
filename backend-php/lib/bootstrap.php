<?php
declare(strict_types=1);

/** Shared helpers for the Nexus NetGuard API (PHP 8.1+). */

function json_response(int $status, array $body = []): never
{
    http_response_code($status);
    header('Content-Type: application/json; charset=utf-8');
    header('X-Content-Type-Options: nosniff');
    if ($status !== 204) {
        echo json_encode($body, JSON_UNESCAPED_SLASHES | JSON_UNESCAPED_UNICODE);
    }
    exit;
}

/** Optional shared-secret check. Set NEXUS_API_TOKEN in the environment to enforce it. */
function require_token(): void
{
    $expected = getenv('NEXUS_API_TOKEN') ?: '';
    if ($expected === '') {
        return;
    }
    $given = $_SERVER['HTTP_X_API_TOKEN'] ?? '';
    if (!is_string($given) || !hash_equals($expected, $given)) {
        json_response(401, ['error' => 'unauthorized']);
    }
}

function db(): PDO
{
    static $pdo = null;
    if ($pdo instanceof PDO) {
        return $pdo;
    }
    $dsn = sprintf(
        'mysql:host=%s;dbname=%s;charset=utf8mb4',
        getenv('DB_HOST') ?: '127.0.0.1',
        getenv('DB_NAME') ?: 'nexus_netguard'
    );
    $pdo = new PDO($dsn, getenv('DB_USER') ?: 'nexus', getenv('DB_PASS') ?: '', [
        PDO::ATTR_ERRMODE => PDO::ERRMODE_EXCEPTION,
        PDO::ATTR_DEFAULT_FETCH_MODE => PDO::FETCH_ASSOC,
        PDO::ATTR_EMULATE_PREPARES => false,
    ]);
    return $pdo;
}

/** Served when the database is unavailable. Mirrors DefaultConfig in the Android app. */
function default_config(): array
{
    return [
        'version' => 1,
        'ping_nodes' => [
            ['name' => 'Cloudflare', 'host' => '1.1.1.1', 'port' => 443],
            ['name' => 'Google', 'host' => '8.8.8.8', 'port' => 443],
            ['name' => 'Quad9', 'host' => '9.9.9.9', 'port' => 443],
        ],
        'dns_servers' => [
            ['name' => 'Cloudflare', 'ip' => '1.1.1.1', 'private_dns' => 'one.one.one.one'],
            ['name' => 'Google', 'ip' => '8.8.8.8', 'private_dns' => 'dns.google'],
            ['name' => 'Quad9', 'ip' => '9.9.9.9', 'private_dns' => 'dns.quad9.net'],
        ],
        'hog_packages' => [
            'com.facebook.katana', 'com.facebook.orca', 'com.instagram.android',
            'com.zhiliaoapp.musically', 'com.ss.android.ugc.trill', 'com.snapchat.android',
            'com.twitter.android', 'com.google.android.youtube', 'com.netflix.mediaclient',
            'com.spotify.music', 'com.android.vending', 'com.google.android.apps.photos',
        ],
    ];
}
