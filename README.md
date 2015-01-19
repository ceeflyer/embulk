# Embulk: plugin-based bulk data loader

## Quick Start

You can install Embulk using [RubyGems](https://rubygems.org/). `embulk bundle`

```
gem install embulk
embulk bundle  data
embulk guess   -b data data/examples/csv.yml -o config.yml
embulk preview -b data config.yml
embulk run     -b data config.yml
```

## Development

### Packaging

```
rake
```

### Build

```
mvn clean package dependency:copy-dependencies && mv -f embulk-cli/target/dependency/*.jar classpath/
./bin/embulk guess examples/config.yml > config.yml
./bin/embulk preview config.yml
./bin/embulk run config.yml
```

You can see JaCoCo's test coverage report at ${project}/target/site/jacoco/index.html

