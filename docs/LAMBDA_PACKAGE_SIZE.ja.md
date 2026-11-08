# Lambda 配布物のサイズ計測

issue #17 の計測結果をまとめる。目的は現状の配布物の大きさと内訳を記録することであり、レイヤー分割や依存の
除外・削除は行っていない。実 AWS 環境へのデプロイもしていない。

## 手順

```sh
sbt -batch lambda/assembly
./deploy/measure-lambda-package.sh
```

`measure-lambda-package.sh` は既存の jar を計測する。次の値を表示する。

- jar の圧縮サイズ（バイト / MiB）と、展開後の合計サイズ（バイト / MiB）
- パッケージ内で大きいエントリの上位 20 件
- 実行時の依存 JAR の上位 20 件（`sbt export lambda/Runtime/fullClasspath` から取得）
- Spanner / Bigtable / SQLite 関連の依存が含まれるか

`lib/` ディレクトリはこの配布物には存在しない。sbt-assembly の fat jar であり、依存 JAR の中身が展開されて
1 つの jar にまとまっているため、`lib/` に入るはずの依存 JAR を別途一覧している。合否のしきい値は設けていない。

## 計測結果

計測日: 2026-11-08。基準コミット: `550de5d`。手元の sbt（Java 21）で計測した。CI は JDK 17 で同じ
スクリプトを実行する。依存のバージョンが変われば値も変わる。

| 項目 | 値 |
|:--|:--|
| jar の圧縮サイズ | 124,431,470 bytes（118.67 MiB） |
| 展開後の合計サイズ | 307,808,701 bytes（293.55 MiB） |
| エントリ数 | 58,974 |
| 実行時の依存 JAR | 169 件、合計 137,493,108 bytes（131.12 MiB） |

Lambda の展開後の上限は 250 MB である。現状の配布物は展開後 293.55 MiB で、この上限を超えている。本 issue では
計測のみを行い、依存の除外やレイヤー分割はしていない。配布方法の見直しは別途判断が必要である。

### 実行時の依存 JAR（大きい順、上位 20 件）

| サイズ (bytes) | サイズ (MiB) | JAR |
|--:|--:|:--|
| 12,310,552 | 11.74 | `grpc-xds-1.83.0.jar` |
| 12,002,370 | 11.45 | `sqlite-jdbc-3.53.4.0.jar` |
| 10,837,690 | 10.34 | `grpc-netty-shaded-1.83.0.jar` |
| 8,682,747 | 8.28 | `bcprov-jdk18on-1.80.jar` |
| 7,548,583 | 7.20 | `conscrypt-openjdk-uber-2.6.2.jar` |
| 7,123,338 | 6.79 | `cats-core_3-2.13.0.jar` |
| 5,924,531 | 5.65 | `scala-library-2.13.15.jar` |
| 5,039,616 | 4.81 | `zio_3-2.1.27.jar` |
| 3,064,793 | 2.92 | `guava-33.6.0-jre.jar` |
| 3,023,981 | 2.88 | `caliban_3-3.2.1.jar` |
| 2,982,430 | 2.84 | `dynamodb-2.57.4.jar` |
| 2,830,165 | 2.70 | `proto-google-common-protos-2.78.0.jar` |
| 2,602,851 | 2.48 | `mysql-connector-j-9.5.0.jar` |
| 2,469,561 | 2.36 | `tink-1.16.0.jar` |
| 2,113,305 | 2.02 | `proto-google-cloud-bigtable-admin-v2-2.85.0.jar` |
| 2,036,851 | 1.94 | `proto-google-cloud-monitoring-v3-3.100.0.jar` |
| 1,955,738 | 1.87 | `google-cloud-spanner-6.124.0.jar` |
| 1,886,534 | 1.80 | `protobuf-java-4.33.6.jar` |
| 1,873,067 | 1.79 | `circe-core_3-0.14.17.jar` |
| 1,827,578 | 1.74 | `proto-google-cloud-bigtable-v2-2.85.0.jar` |

### パッケージ内の大きいエントリ（上位 20 件）

上位 20 件のうち 19 件は `sqlite-jdbc` が同梱するプラットフォーム別のネイティブライブラリである。残る 1 件は
AWS SDK のリージョン定義 `software/amazon/awssdk/regions/internal/region/endpoints.json` である。

| サイズ (bytes) | エントリ |
|--:|:--|
| 1,330,224 | `org/sqlite/native/Linux/ppc64/libsqlitejdbc.so` |
| 1,288,092 | `org/sqlite/native/Mac/x86_64/libsqlitejdbc.dylib` |
| 1,253,387 | `software/amazon/awssdk/regions/internal/region/endpoints.json` |
| 1,220,120 | `org/sqlite/native/FreeBSD/aarch64/libsqlitejdbc.so` |
| 1,125,376 | `org/sqlite/native/Windows/aarch64/sqlitejdbc.dll` |
| 1,124,176 | `org/sqlite/native/Mac/aarch64/libsqlitejdbc.dylib` |
| 1,117,208 | `org/sqlite/native/Linux-Musl/aarch64/libsqlitejdbc.so` |
| 1,113,360 | `org/sqlite/native/Linux/aarch64/libsqlitejdbc.so` |
| 1,104,256 | `org/sqlite/native/Linux-Musl/x86_64/libsqlitejdbc.so` |
| 1,097,664 | `org/sqlite/native/Linux/x86_64/libsqlitejdbc.so` |
| 1,091,960 | `org/sqlite/native/Linux-Musl/x86/libsqlitejdbc.so` |
| 1,081,280 | `org/sqlite/native/FreeBSD/x86_64/libsqlitejdbc.so` |
| 1,014,784 | `org/sqlite/native/Windows/x86_64/sqlitejdbc.dll` |
| 994,056 | `org/sqlite/native/Linux/arm/libsqlitejdbc.so` |
| 985,884 | `org/sqlite/native/Linux/armv6/libsqlitejdbc.so` |
| 985,112 | `org/sqlite/native/Linux/x86/libsqlitejdbc.so` |
| 981,784 | `org/sqlite/native/Linux/armv7/libsqlitejdbc.so` |
| 976,996 | `org/sqlite/native/FreeBSD/x86/libsqlitejdbc.so` |
| 953,856 | `org/sqlite/native/Windows/x86/sqlitejdbc.dll` |
| 832,000 | `org/sqlite/native/Windows/armv7/sqlitejdbc.dll` |

## ESA Java 経由の Spanner / Bigtable / SQLite 依存

`event-store-adapter-scala` 5.1.0 が依存する `event-store-adapter-java` 5.1.0 の POM は、次の 3 つを宣言して
いる。Spanner と Bigtable は `compile`、SQLite は `runtime` スコープである。

| 依存 | バージョン | スコープ |
|:--|:--|:--|
| `com.google.cloud:google-cloud-spanner` | 6.124.0 | compile |
| `com.google.cloud:google-cloud-bigtable` | 2.85.0 | compile |
| `org.xerial:sqlite-jdbc` | 3.53.4.0 | runtime |

いずれも fat jar に含まれていることを、実行時クラスパスの JAR 名とパッケージ内のクラスで確認した。関連する
推移的依存も含まれる。

- Spanner: `google-cloud-spanner-6.124.0.jar`、`proto-google-cloud-spanner-*-6.124.0.jar`、
  `grpc-google-cloud-spanner-*-6.124.0.jar`
- Bigtable: `google-cloud-bigtable-2.85.0.jar`、`proto-google-cloud-bigtable-*-2.85.0.jar`、
  `grpc-google-cloud-bigtable-v2-2.85.0.jar`
- SQLite: `sqlite-jdbc-3.53.4.0.jar` と、パッケージ内の `org/sqlite/native/**` のネイティブライブラリ

## CI

`deploy-assets` ジョブは `sbt lambda/assembly` の後に `./deploy/measure-lambda-package.sh` を実行し、サイズ、
上位エントリ、依存 JAR 一覧、ESA バックエンド依存の有無をログに出す。合否のしきい値は設けていない。
`deploy/measure-lambda-package.sh` の構文も `bash -n` で検査する。

## 残課題

- 展開後のサイズが Lambda の上限 250 MB を超えている。依存の除外やレイヤー分割は本 issue の対象外なので、
  配布方法の見直しは別途判断が必要である。
- 計測値は依存のバージョンに依存する。依存を更新したときは再計測する。
