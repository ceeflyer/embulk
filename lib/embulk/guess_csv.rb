module Embulk
  require_relative 'time_format_guess'

  class GuessCsv < LineGuessPlugin
    Plugin.register_guess('csv', self)

    DELIMITER_CANDIDATES = [
      ",", "\t", "|"
    ]

    QUOTE_CANDIDATES = [
      "\"", "'"
    ]

    def guess_lines(config, sample_lines)
      delim = guess_delimiter(sample_lines)
      unless delim
        # not CSV file
        return {}
      end

      parser_config = config["parser"] || {}
      parser_guessed = {"type" => "csv", "delimiter" => delim}

      quote = guess_quote(sample_lines, delim)
      parser_guessed["quote"] = quote ? quote : ''

      sample_records = sample_lines.map {|line| line.split(delim) }  # TODO use CsvTokenizer
      first_types = guess_field_types(sample_records[0, 1])
      other_types = guess_field_types(sample_records[1..-1])

      if first_types.size <= 1 || other_types.size <= 1
        # guess failed
        return {}
      end

      unless parser_config.has_key?("header_line")
        parser_guessed["header_line"] = (first_types != other_types && !first_types.any? {|t| t != ["string"] })
      end

      unless parser_config.has_key?("columns")
        if parser_guessed["header_line"] || parser_config["header_line"]
          column_names = sample_records.first
        else
          column_names = (0..other_types.size).to_a.map {|i| "c#{i}" }
        end
        schema = []
        column_names.zip(other_types).each do |name,(type,format)|
          if name && type
            if format
              schema << {"name" => name, "type" => type, "format" => format}
            else
              schema << {"name" => name, "type" => type}
            end
          end
        end
        parser_guessed["columns"] = schema
      end

      return {"parser" => parser_guessed}
    end

    private

    def guess_delimiter(sample_lines)
      delim_weights = DELIMITER_CANDIDATES.map do |d|
        counts = sample_lines.map {|line| line.count(d) }
        total = array_sum(counts)
        if total > 0
          stddev = array_standard_deviation(counts)
          stddev = 0.000000001 if stddev == 0.0
          weight = total / stddev
          [d, weight]
        else
          [nil, 0]
        end
      end

      delim, weight = *delim_weights.sort_by {|d,weight| weight }.last
      if delim != nil && weight > 1
        return delim
      else
        return nil
      end
    end

    def guess_quote(sample_lines, delim)
      delim_regexp = Regexp.escape(delim)
      quote_weights = QUOTE_CANDIDATES.map do |q|
        weights = sample_lines.map do |line|
          q_regexp = Regexp.escape(q)
          count = line.count(q)
          if count > 0
            weight = count
            weight += line.scan(/(?:\A|#{delim_regexp})\s*#{q_regexp}(?:(?!#{q_regexp}).)*\s*#{q_regexp}(?:$|#{delim_regexp})/).size * 20
            weight += line.scan(/(?:\A|#{delim_regexp})\s*#{q_regexp}(?:(?!#{delim_regexp}).)*\s*#{q_regexp}(?:$|#{delim_regexp})/).size * 40
            weight
          else
            nil
          end
        end.compact
        weights.empty? ? 0 : array_avg(weights)
      end
      quote, weight = QUOTE_CANDIDATES.zip(quote_weights).sort_by {|q,w| w }.last
      if weight >= 10.0
        return quote
      else
        return nil
      end
    end

    def guess_field_types(field_lines)
      column_lines = []
      field_lines.each do |fields|
        fields.each_with_index {|field,i| (column_lines[i] ||= []) << guess_type(field) }
      end
      columns = column_lines.map do |types|
        t = types.inject(nil) {|r,t| merge_type(r,t) } || "string"
        if t.is_a?(TimestampMatch)
          format = TimeFormatGuess.guess(types.map {|type| type.text })
          ["timestamp", format]
        else
          [t]
        end
      end
      return columns
    end

    TYPE_COALESCE = Hash[{
      long: :double,
      boolean: :long,
    }.map {|k,v|
      [[k.to_s, v.to_s].sort, v.to_s]
    }]

    def merge_type(type1, type2)
      if type1 == type2
        type1
      elsif type1.nil? || type2.nil?
        type1 || type2
      else
        TYPE_COALESCE[[type1, type2].sort] || "string"
      end
    end

    class TimestampMatch < String
      def initialize(text)
        super("timestamp")
        @text = text
      end
      attr_reader :text
    end

    def guess_type(str)
      if ["true", "false"].include?(str)
        return "boolean"
      end

      if TimeFormatGuess.guess(str)
        return TimestampMatch.new(str)
      end

      if str.to_i.to_s == str
        return "long"
      end

      if str.include?('.')
        a, b = str.split(".", 2)
        if a.to_i.to_s == a && b.to_i.to_s == b
          return "double"
        end
      end

      return "string"
    end

    def array_sum(array)
      array.inject(0) {|r,i| r += i }
    end

    def array_avg(array)
      array.inject(0.0) {|r,i| r += i } / array.size
    end

    def array_variance(array)
      avg = array_avg(array)
      array.inject(0.0) {|r,i| r += (i - avg) ** 2 } / array.size
    end

    def array_standard_deviation(array)
      Math.sqrt(array_variance(array))
    end
  end

end
