package org.sigmah.server.service.util;

/*
 * #%L
 * Sigmah
 * %%
 * Copyright (C) 2010 - 2016 URD
 * %%
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 * 
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public
 * License along with this program.  If not, see
 * <http://www.gnu.org/licenses/gpl-3.0.html>.
 * #L%
 */

import java.util.Map;
import java.util.Set;

/**
 * Property map.
 * 
 * @author Denis Colliot (dcolliot@ideia.fr)
 */
public class PropertyMap {

	/**
	 * Inner map.
	 */
	private final Map<String, Object> map;

	public PropertyMap(Map<String, Object> map) {
		this.map = map;
	}

	@SuppressWarnings("unchecked")
	public <X> X get(String propertyName) {
		return (X) map.get(propertyName);
	}

	public boolean containsKey(String propertyName) {
		return map.containsKey(propertyName);
	}

	public Set<Map.Entry<String, Object>> entrySet() {
		return map.entrySet();
	}
}
