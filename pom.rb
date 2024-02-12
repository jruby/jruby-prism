project 'JRuby Prism' do
  model_version '4.0.0'
  inception_year '2001'
  id 'org.jruby:jruby-prism', '0.21.0'
  inherit 'org.sonatype.oss:oss-parent:7'
  packaging 'pom'

  description 'JRuby Prism is a plugin parser for the JRuby project.'
  organization 'JRuby', 'https://www.jruby.org'

  [ 'enebo' ].each do |name|
    developer name do
      name name
      roles 'developer'
    end
  end

  issue_management 'https://github.com/jruby/jruby_prism/issues', 'GitHub'

  mailing_list "jruby" do
    archives "https://github.com/jruby/jruby/wiki/MailingLists"
  end

  license 'GPL-2.0', 'http://www.gnu.org/licenses/gpl-2.0-standalone.html'
  license 'LGPL-2.1', 'http://www.gnu.org/licenses/lgpl-2.1-standalone.html'
  license 'EPL-2.0', 'http://www.eclipse.org/legal/epl-v20.html'

  plugin_repository( :url => 'https://oss.sonatype.org/content/repositories/snapshots/',
                     :id => 'sonatype' ) do
    releases 'false'
    snapshots 'true'
  end
  repository( :url => 'https://oss.sonatype.org/content/repositories/snapshots/',
              :id => 'sonatype' ) do
    releases 'false'
    snapshots 'true'
  end

  source_control( :url => 'https://github.com/jruby/jruby',
                  :connection => 'scm:git:git@jruby.org:jruby.git',
                  :developer_connection => 'scm:git:ssh://git@jruby.org/jruby.git' )

  distribution do
    site( :url => 'https://github.com/jruby/jruby',
          :id => 'gh-pages',
          :name => 'JRuby Site' )
  end

  properties( 'polyglot.dump.pom' => 'pom.xml',
              'polyglot.dump.readonly' => true,
              'base.java.version' => '1.8',
              'base.javac.version' => '1.8',
              'maven.build.timestamp.format' => 'yyyy-MM-dd',
              'build.date' => '${maven.build.timestamp}',
              'create.sources.jar' => true )

  dependencies do
    jar 'org.jruby:jruby-base:9.4.6.0-SNAPSHOT'
#    jar 'com.dylibso.chicory:runtime:999-SNAPSHOT'
  end

  plugin(:jar,
         archive: {manifestEntries: {'Automatic-Module-Name' => 'org.jruby'}})

  plugin_management do
    plugin :source, '3.2.1'
    plugin :install, '3.0.0-M1'
    plugin :deploy, '3.0.0-M1'
    plugin :javadoc, '3.2.0'
    plugin :release, '3.0.0-M1'
    plugin :resources, '3.2.0'
  end

  plugin( :compiler,
          'encoding' => 'utf-8',
          'verbose' => 'false',
          'showWarnings' => 'true',
          'showDeprecation' => 'true',
          'source' => [ '${base.java.version}', '1.8' ],
          'target' => [ '${base.javac.version}', '1.8' ],
          'useIncrementalCompilation' =>  'false' ) do
    execute_goals( 'compile')
  end

  build do
    default_goal 'package'

    resource do
      directory 'src/main/java'
    end

    resource do
      directory 'src/main/resources'
      includes 'META-INF/**/*'
    end

    # FIXME: This should be handled by resources above it.
    resource do
      directory 'src/main/resources/wasm'
      includes '**/*wasm'
    end

  end

  packaging("jar")
end
