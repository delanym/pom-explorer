package fr.lteconsulting.pomexplorer.tools;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collector;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import fr.lteconsulting.pomexplorer.Session;
import fr.lteconsulting.pomexplorer.graph.PomGraph.PomGraphReadTransaction;
import fr.lteconsulting.pomexplorer.model.Gav;

/**
 * Commodity class to handle GAV filtering by name in commands
 */
public class FilteredGAVs
{
	private final String[] filters;

	public FilteredGAVs( String filter )
	{
		if( filter != null )
		{
			filter = filter.toLowerCase();
			filters = filter.split( "," );
		}
		else
		{
			filters = null;
		}
	}

	public String getFilterDescription()
	{
		return Arrays.stream( filters ).collect( Collectors.joining( ", " ) );
	}

	public List<Gav> getGavs( Session session )
	{
		PomGraphReadTransaction tx = session.graph().read();

		Stream<Gav> stream;

		if( filters != null )
			stream = tx.gavs()
					.stream()
					.filter( this::accept );
		else
			stream = tx.gavs().stream();

		List<Gav> res = new ArrayList<>();

		stream.sorted( Gav.alphabeticalComparator ).forEachOrdered( gav -> res.add( gav ) );

		return res;
	}

	public boolean accept( Gav gav )
	{
		if( filters == null )
			return false;

		String toSearch = gav.toString().toLowerCase();
		return Arrays.stream( filters ).anyMatch( filter -> matches( toSearch, filter ) );
	}

	/**
	 * A filter without wildcard matches as a substring. The <code>*</code> and
	 * <code>?</code> wildcards are also supported, so <code>com.traderoot*</code>
	 * matches every gav whose group id starts with <code>com.traderoot</code>.
	 */
	private static boolean matches( String value, String filter )
	{
		if( filter.indexOf( '*' ) < 0 && filter.indexOf( '?' ) < 0 )
			return value.contains( filter );

		StringBuilder regex = new StringBuilder();
		for( char c : filter.toCharArray() )
		{
			switch( c )
			{
				case '*':
					regex.append( ".*" );
					break;
				case '?':
					regex.append( '.' );
					break;
				default:
					regex.append( Pattern.quote( String.valueOf( c ) ) );
					break;
			}
		}
		return value.matches( regex.toString() );
	}

	@Override
	public String toString()
	{
		StringBuilder sb = new StringBuilder();
		sb.append( "[FilteredGAVs:" );
		if( filters != null )
		{
			for( String filter : filters )
			{
				sb.append( " " );
				sb.append( filter );
			}
		}
		sb.append( "]" );

		return sb.toString();
	}
}
