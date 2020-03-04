/*
 * Hibernate, Relational Persistence for Idiomatic Java
 *
 * License: GNU Lesser General Public License (LGPL), version 2.1 or later
 * See the lgpl.txt file in the root directory or http://www.gnu.org/licenses/lgpl-2.1.html
 */
package org.hibernate.hql.internal.ast.tree;

import java.util.List;

import org.hibernate.QueryException;
import org.hibernate.hql.internal.ast.HqlSqlWalker;
import org.hibernate.hql.internal.ast.SqlASTFactory;
import org.hibernate.persister.collection.CollectionPersister;
import org.hibernate.persister.collection.QueryableCollection;
import org.hibernate.persister.entity.EntityPersister;
import org.hibernate.persister.entity.PropertyMapping;
import org.hibernate.type.CollectionType;
import org.hibernate.type.CompositeType;
import org.hibernate.type.EntityType;
import org.hibernate.type.Type;

import antlr.SemanticException;
import antlr.collections.AST;

/**
 * @author Steve Ebersole
 */
public class CollectionPathNode extends SqlNode {
	/**
	 * Used to resolve the collection "owner key" columns
 	 */
	private final FromElement ownerFromElement;

	private final CollectionPersister collectionDescriptor;

	private final String collectionPropertyName;
	private final String collectionPropertyPath;
	private final String collectionQueryPath;



	/**
	 * Instantiate a `CollectionPathNode`
	 *
	 * @see #from(AST, AST, HqlSqlWalker)
	 */
	public CollectionPathNode(
			FromElement ownerFromElement,
			CollectionPersister collectionDescriptor,
			String collectionPropertyName,
			String collectionQueryPath,
			String collectionPropertyPath) {
		this.ownerFromElement = ownerFromElement;
		this.collectionDescriptor = collectionDescriptor;
		this.collectionPropertyName = collectionPropertyName;
		this.collectionQueryPath = collectionQueryPath;
		this.collectionPropertyPath = collectionPropertyPath;

		super.setType( SqlASTFactory.COLL_PATH );
		super.setDataType( collectionDescriptor.getCollectionType() );
		super.setText( collectionDescriptor.getRole() );
	}

	/**
	 * Factory for `CollectionPathNode` instances
	 *
	 * @param qualifier The left-hand-side of a dot-ident node - may be null to indicate an ident arg
	 * @param reference The right-hand-side of the dot-ident or the ident that is an unqualified reference
	 */
	public static CollectionPathNode from(
			AST qualifier,
			AST reference,
			HqlSqlWalker walker) {

		final String referenceName = reference.getText();
		final String qualifierQueryPath = qualifier == null
				? ""
				: ( (FromReferenceNode) qualifier ).getPath();
		final String referencePath = qualifier == null
				? referenceName
				: qualifierQueryPath + "." + reference;

		if ( qualifier == null ) {
			// If there is no qualifier it means the argument to `size()` was a simple IDENT node as opposed to a DOT-IDENT
			// node.  In this case, `reference` could technically be a join alias.  This is not JPA
			// compliant, but is a Hibernate-specific extension

			// size( cu )

			final FromElement byAlias = walker.getCurrentFromClause().getFromElement( referenceName );

			if ( byAlias != null ) {
				final FromElement ownerRef = byAlias.getOrigin();
				final QueryableCollection collectionDescriptor = byAlias.getQueryableCollection();

				return new CollectionPathNode(
						ownerRef,
						collectionDescriptor,
						referenceName,
						referencePath,
						referenceName
				);
			}
			else {
				// we (should) have an unqualified plural-attribute name - look through all of the defined from-elements
				// and look for one that exposes that property

				//noinspection unchecked
				final List<FromElement> fromElements = walker.getCurrentFromClause().getExplicitFromElements();

				if ( fromElements.size() == 1 ) {
					final FromElement ownerRef = fromElements.get( 0 );

					final PropertyMapping collectionPropertyMapping = ownerRef.getPropertyMapping( referenceName );

					//noinspection RedundantClassCall
					if ( ! CollectionType.class.isInstance( collectionPropertyMapping.getType() ) ) {
						throw new QueryException( "Could not resolve identifier `" + referenceName + "` as plural-attribute" );
					}

					final CollectionType collectionType = (CollectionType) collectionPropertyMapping.getType();

					return new CollectionPathNode(
							ownerRef,
							walker.getSessionFactoryHelper().requireQueryableCollection( collectionType.getRole() ),
							referenceName,
							referencePath,
							referenceName
					);
				}
				else {
					FromElement discoveredQualifier = null;

					//noinspection ForLoopReplaceableByForEach
					for ( int i = 0; i < fromElements.size(); i++ ) {
						final FromElement fromElement = fromElements.get( i );
						try {
							final PropertyMapping propertyMapping = fromElement.getPropertyMapping( referenceName );
							//noinspection RedundantClassCall
							if ( ! CollectionType.class.isInstance( propertyMapping.getType() ) ) {
								throw new QueryException( "Could not resolve identifier `" + referenceName + "` as plural-attribute" );
							}

							discoveredQualifier = fromElement;

							break;
						}
						catch (Exception e) {
							// try the next
						}
					}

					if ( discoveredQualifier == null ) {
						throw new QueryException( "Could not resolve identifier `" + referenceName + "` as plural-attribute" );
					}

					final FromElement ownerRef = discoveredQualifier;

					final PropertyMapping collectionPropertyMapping = ownerRef.getPropertyMapping( referenceName );

					//noinspection RedundantClassCall
					if ( ! CollectionType.class.isInstance( collectionPropertyMapping.getType() ) ) {
						throw new QueryException( "Could not resolve identifier `" + referenceName + "` as plural-attribute" );
					}

					final CollectionType collectionType = (CollectionType) collectionPropertyMapping.getType();

					return new CollectionPathNode(
							ownerRef,
							walker.getSessionFactoryHelper().requireQueryableCollection( collectionType.getRole() ),
							referenceName,
							referencePath,
							referenceName
					);
				}
			}
		}
		else {
			// we have a dot-ident structure
			final FromReferenceNode qualifierFromReferenceNode = (FromReferenceNode) qualifier;
			try {
				qualifierFromReferenceNode.resolve( false, false );
			}
			catch (SemanticException e) {
				throw new QueryException( "Unable to resolve collection-path qualifier : " + qualifier.getText(), e );
			}

			final Type qualifierType = qualifierFromReferenceNode.getDataType();
			final FromElement ownerRef = ( (FromReferenceNode) qualifier ).getFromElement();

			final CollectionType collectionType;
			final String mappedPath;

			if ( qualifierType instanceof CompositeType ) {
				final CompositeType qualifierCompositeType = (CompositeType) qualifierType;
				final int collectionPropertyIndex = (qualifierCompositeType).getPropertyIndex( referenceName );
				collectionType = (CollectionType) qualifierCompositeType.getSubtypes()[collectionPropertyIndex];

				if ( ownerRef instanceof ComponentJoin ) {
					mappedPath = ( (ComponentJoin) ownerRef ).getComponentPath() + "." + referenceName;
				}
				else {
					mappedPath = qualifierQueryPath.substring( qualifierQueryPath.indexOf( "." ) + 1 );
				}
			}
			else if ( qualifierType instanceof EntityType ) {
				final EntityType qualifierEntityType = (EntityType) qualifierType;
				final String entityName = qualifierEntityType.getAssociatedEntityName();
				final EntityPersister entityPersister = walker.getSessionFactoryHelper().findEntityPersisterByName( entityName );
				final int propertyIndex = entityPersister.getEntityMetamodel().getPropertyIndex( referenceName );
				collectionType = (CollectionType) entityPersister.getPropertyTypes()[ propertyIndex ];
				mappedPath = referenceName;
			}
			else {
				throw new QueryException( "Unexpected collection-path reference qualifier type : " + qualifier );
			}

			return new CollectionPathNode(
					( (FromReferenceNode) qualifier ).getFromElement(),
					walker.getSessionFactoryHelper().requireQueryableCollection( collectionType.getRole() ),
					referenceName,
					referencePath,
					mappedPath
			);
		}
	}

	public FromElement getCollectionOwnerRef() {
		return ownerFromElement;
	}

	public CollectionPersister getCollectionDescriptor() {
		return collectionDescriptor;
	}

	public String getCollectionPropertyName() {
		return collectionPropertyName;
	}

	public String getCollectionPropertyPath() {
		return collectionPropertyPath;
	}

	public String getCollectionQueryPath() {
		return collectionQueryPath;
	}
}