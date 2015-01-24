require 'java'

#
# this file is loaded by embulk/java/bootstrap.rb
#

module Embulk::Java
  java_import 'org.embulk.spi.Buffer'

  java_import 'org.embulk.config.DataSourceImpl'
  java_import 'org.embulk.spi.time.Timestamp'
  java_import 'org.embulk.spi.GuessPlugin'
  java_import 'org.embulk.spi.OutputPlugin'
  java_import 'org.embulk.spi.InputPlugin'
  java_import 'org.embulk.spi.TransactionalPageOutput'
  java_import 'org.embulk.spi.PageReader'
  java_import 'org.embulk.spi.PageBuilder'
  java_import 'org.embulk.spi.util.LineDecoder'
  java_import 'org.embulk.spi.util.ListFileInput'
  java_import 'org.embulk.spi.Schema'
  java_import 'org.embulk.spi.Column'
  java_import 'org.embulk.spi.type.Type'
  java_import 'org.embulk.spi.type.Types'

  # TODO
end
