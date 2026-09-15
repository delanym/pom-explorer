package fr.lteconsulting.pomexplorer;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import fr.lteconsulting.pomexplorer.model.Gav;
import fr.lteconsulting.pomexplorer.tools.FilteredGAVs;

public class GraphQuery
{
	private final Set<Gav> roots;

	private final FilteredGAVs filter;

	private final static Map<String, GraphQuery> queries = new HashMap<>();

	public static String register( Set<Gav> roots )
	{
		return register( roots, null );
	}

	public static String register( Set<Gav> roots, FilteredGAVs filter )
	{
		String id = Integer.toHexString( System.identityHashCode( new Object() ) );
		queries.put( id, new GraphQuery( roots, filter ) );
		return id;
	}

	public static GraphQuery get( String id )
	{
		return queries.get( id );
	}

	public GraphQuery( Set<Gav> roots, FilteredGAVs filter )
	{
		this.roots = roots;
		this.filter = filter;
	}

	public Set<Gav> getRoots()
	{
		return roots;
	}

	/**
	 * Filter which gavs are included in the graph, or null when the whole graph
	 * should be sent to the client.
	 */
	public FilteredGAVs getFilter()
	{
		return filter;
	}
}
